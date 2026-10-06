package com.bencodez.advancedcore.api.user.usercache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.user.*;
import com.bencodez.advancedcore.api.user.usercache.change.UserDataChangeInt;
import com.bencodez.advancedcore.core.user.runtime.*;
import com.bencodez.advancedcore.core.user.storage.SqlUserStorage;
import com.bencodez.advancedcore.core.user.storage.sql.*;
import com.bencodez.simpleapi.sql.DataType;
import com.bencodez.simpleapi.sql.data.*;

/** Real fork cache + shared coordinator + JDBC; manager wiring remains a controlled adapter. */
@Timeout(15)
class SharedNativeCacheRuntimeTest {
    @TempDir Path directory;

    private SqliteUserBackend backend(String name) {
        return new SqliteUserBackend(directory, name, "Users", SqlUserSchema.builder()
                .column("Points", "INTEGER", DataType.INTEGER).build(), SqlBackendLogger.NO_OP);
    }

    @Test void sharedFlushAndShutdownPersistNativeQueueWithoutCallingLegacyProvider() throws Exception {
        Owner owner = new Owner();
        SqliteUserBackend backend = backend("flush");
        SharedUserDataRuntime runtime = new SharedUserDataRuntime(backend, owner);
        runtime.queueChange(owner.uuid, "Points", new DataValueInt(7));
        assertEquals(7, owner.cache().getCachedValue("Points").getInt());
        runtime.flush(owner.uuid);
        assertEquals(7, backend.user(owner.uuid).readRow(UserStorage.SQLITE).stream()
                .filter(column -> column.getName().equals("Points")).findFirst().get().getValue().getInt());
        assertFalse(owner.cache().hasChangesToProcess());
        verify(owner.users).onChange(eq(owner.user), any(String[].class));
        runtime.queueChange(owner.uuid, "Points", new DataValueInt(9));
        runtime.close();
        assertTrue(runtime.isClosed());
        assertTrue(owner.caches.isEmpty());
        verify(owner.data, never()).setValuesStrict(any());
        try (SqliteUserBackend reopened = backend("flush")) {
            assertEquals(9, reopened.user(owner.uuid).readRow(UserStorage.SQLITE).stream()
                    .filter(column -> column.getName().equals("Points")).findFirst().get().getValue().getInt());
        }
    }

    @Test void transactionFlushesOldQueueThenRetiresCacheOnlyAfterCommit() {
        Owner owner = new Owner();
        try (SharedUserDataRuntime runtime = new SharedUserDataRuntime(backend("transaction"), owner)) {
            runtime.queueChange(owner.uuid, "Points", new DataValueInt(3));
            assertEquals("committed", runtime.transaction(owner.uuid, scope -> {
                assertEquals(3, scope.readRow().stream().filter(column -> column.getName().equals("Points"))
                        .findFirst().get().getValue().getInt());
                scope.writeValues(Collections.singletonMap("Points", new DataValueInt(11)));
                return "committed";
            }));
            assertTrue(owner.caches.isEmpty());
            assertEquals(11, runtime.read(owner.uuid, "Points", UserDataFetchMode.DEFAULT, null,
                    new DataValueInt(0)).getInt());
            assertThrows(IllegalStateException.class, () -> runtime.transaction(owner.uuid, scope -> {
                scope.writeValues(Collections.singletonMap("Points", new DataValueInt(99)));
                throw new IllegalStateException("rollback");
            }));
            assertEquals(11, runtime.read(owner.uuid, "Points", UserDataFetchMode.DEFAULT, null,
                    new DataValueInt(0)).getInt());
        }
    }

    @Test void failedSharedWriteRetainsNativeQueueAndRetryDoesNotUseLegacyStorage() throws Exception {
        Owner owner = new Owner();
        try (SharedUserDataRuntime runtime = new SharedUserDataRuntime(backend("retry"), owner)) {
            runtime.queueChange(owner.uuid, "Points", new DataValueInt(4));
            UserDataCache cache = owner.cache();
            cache.setSharedStorageWriter(values -> { throw new IllegalStateException("unavailable"); });
            assertThrows(IllegalStateException.class, cache::processChangesForSharedRuntime);
            assertTrue(cache.hasChangesToProcess());
            verify(owner.users, never()).onChange(any(), any(String[].class));
            runtime.flush(owner.uuid);
            assertEquals(4, runtime.read(owner.uuid, "Points", UserDataFetchMode.NO_CACHE, null,
                    new DataValueInt(0)).getInt());
            verify(owner.data, never()).setValuesStrict(any());
        }
    }

    @Test void oldPopulationSnapshotCannotOverwriteACommittedNativeCacheMutation() {
        Owner owner = new Owner();
        try (SharedUserDataRuntime runtime = new SharedUserDataRuntime(backend("snapshot"), owner)) {
            runtime.populate(owner.uuid);
            UserDataCache cache = owner.cache();
            long version = cache.getSharedSnapshotVersion();
            runtime.queueChange(owner.uuid, "Points", new DataValueInt(8));
            runtime.flush(owner.uuid);
            HashMap<String, DataValue> stale = new HashMap<>();
            stale.put("Points", new DataValueInt(1));
            assertEquals(8, cache.updateSharedSnapshot(stale, version).get("Points").getInt());
            assertThrows(IllegalArgumentException.class, () -> cache.updateSharedSnapshot(stale, -1));
        }
    }

    @Test void retirementRefusesUnflushedChangesAndRejectsASecondRuntimeBinding() {
        Owner owner = new Owner();
        try (SharedUserDataRuntime runtime = new SharedUserDataRuntime(backend("retire"), owner)) {
            runtime.queueChange(owner.uuid, "Points", new DataValueInt(5));
            UserDataCache cache = owner.cache();
            assertThrows(IllegalStateException.class, cache::retireAfterSharedFlush);
            assertThrows(IllegalStateException.class, () -> cache.configureSharedStorage(values -> {}, Runnable::run));
            runtime.flush(owner.uuid);
            cache.retireAfterSharedFlush();
            cache.updateCache(new HashMap<>());
            assertNull(cache.getCache());
            assertThrows(IllegalStateException.class, () -> cache.setSharedStorageWriter(values -> {}));
            owner.caches.clear();
        }
    }

    @Test void checkpointStagesNewerMutationAndPublishesNotificationBeforeItsImmediateFlush() throws Exception {
        Owner owner = new Owner();
        try (SharedUserDataRuntime runtime = new SharedUserDataRuntime(backend("checkpoint"), owner)) {
            runtime.queueChange(owner.uuid, "Points", new DataValueInt(2));
            UserDataCache cache = owner.cache();
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            List<String> order = new ArrayList<>();
            try {
                Future<?> checkpoint = worker.submit(() -> cache.flushChangesAndRun(() -> {
                    entered.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                    runtime.backend().user(owner.uuid).write(UserStorage.SQLITE, "Points", new DataValueInt(6));
                    cache.updateCache(new HashMap<>(Collections.singletonMap("Points", new DataValueInt(6))));
                    order.add("checkpoint");
                }));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTrue(cache.tryAddChangeBeforeDeferredSharedFlush(new UserDataChangeInt("Points", 9), () -> {
                    assertEquals(9, cache.getCachedValue("Points").getInt());
                    assertEquals(6, runtime.read(owner.uuid, "Points", UserDataFetchMode.NO_CACHE, null,
                            new DataValueInt(0)).getInt());
                    order.add("notification");
                }, true));
                assertEquals(9, cache.getCachedValue("Points").getInt());
                release.countDown();
                checkpoint.get(5, TimeUnit.SECONDS);
                assertEquals(Arrays.asList("checkpoint", "notification"), order);
                assertTrue(cache.hasChangesToProcess());
                assertEquals(1, owner.immediate.size());
                owner.immediate.remove(0).run();
                assertEquals(9, runtime.read(owner.uuid, "Points", UserDataFetchMode.NO_CACHE, null,
                        new DataValueInt(0)).getInt());
                assertFalse(cache.hasChangesToProcess());
            } finally {
                release.countDown();
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test void failedCheckpointStillReleasesStagedMutationWithoutRetryingItsAcknowledgedPrefix() throws Exception {
        Owner owner = new Owner();
        try (SharedUserDataRuntime runtime = new SharedUserDataRuntime(backend("failed-checkpoint"), owner)) {
            runtime.queueChange(owner.uuid, "Points", new DataValueInt(2));
            UserDataCache cache = owner.cache();
            IllegalStateException failure = new IllegalStateException("checkpoint failed");
            assertSame(failure, assertThrows(IllegalStateException.class, () -> cache.flushChangesAndRun(() -> {
                assertTrue(cache.tryAddChangeBeforeDeferredSharedFlush(new UserDataChangeInt("Points", 7)));
                throw failure;
            })));
            assertEquals(7, cache.getCachedValue("Points").getInt());
            assertEquals(2, runtime.read(owner.uuid, "Points", UserDataFetchMode.NO_CACHE, null,
                    new DataValueInt(0)).getInt());
            runtime.flush(owner.uuid);
            assertEquals(7, runtime.read(owner.uuid, "Points", UserDataFetchMode.NO_CACHE, null,
                    new DataValueInt(0)).getInt());
            verify(owner.users, times(2)).onChange(eq(owner.user), any(String[].class));
        }
    }

    @Test void sharedBindingCannotSwitchAnAlreadyClaimedLegacyBatch() throws Exception {
        Owner owner = new Owner();
        UserDataCache cache = new UserDataCache(owner.manager, owner.uuid);
        owner.caches.put(owner.uuid, cache);
        java.util.concurrent.atomic.AtomicInteger sharedWrites = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            assertThrows(IllegalStateException.class,
                    () -> cache.configureSharedStorage(values -> sharedWrites.incrementAndGet(), Runnable::run));
            assertThrows(IllegalStateException.class,
                    () -> cache.setSharedStorageWriter(values -> sharedWrites.incrementAndGet()));
            return null;
        }).when(owner.data).setValuesStrict(any());
        cache.addChange(new UserDataChangeInt("Points", 3), true);
        cache.processChanges();
        verify(owner.data).setValuesStrict(any());
        assertEquals(0, sharedWrites.get());
        assertFalse(cache.hasChangesToProcess());
    }

    @Test void legacyPublicFlushKeepsNativeAdmissionThroughItsSynchronousCallback() throws Exception {
        Owner owner = new Owner();
        UserDataCache cache = new UserDataCache(owner.manager, owner.uuid);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        doAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(owner.users).onChange(eq(owner.user), any(String[].class));
        try {
            cache.addChange(new UserDataChangeInt("Points", 1), true);
            Future<?> flush = workers.submit(cache::processChanges);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Future<?> retirement = workers.submit(() -> owner.plugin.getUserStorageOwnership()
                    .retire(5, TimeUnit.SECONDS, () -> {}, () -> closed.set(true)));
            assertThrows(TimeoutException.class, () -> retirement.get(100, TimeUnit.MILLISECONDS));
            assertFalse(closed.get());
            release.countDown();
            flush.get(5, TimeUnit.SECONDS);
            retirement.get(5, TimeUnit.SECONDS);
            assertTrue(closed.get());
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void failedStagedConversionKeepsAcceptedPayloadForExplicitFlushRetry() {
        Owner owner = new Owner();
        try (SharedUserDataRuntime runtime = new SharedUserDataRuntime(backend("staged-retry"), owner)) {
            runtime.populate(owner.uuid);
            UserDataCache cache = owner.cache();
            UserDataChangeInt change = spy(new UserDataChangeInt("Points", 12));
            IllegalStateException failure = new IllegalStateException("conversion failed");
            java.util.concurrent.atomic.AtomicInteger notifications = new java.util.concurrent.atomic.AtomicInteger();
            assertSame(failure, assertThrows(IllegalStateException.class, () -> cache.flushChangesAndRun(() -> {
                assertTrue(cache.tryAddChangeBeforeDeferredSharedFlush(change, notifications::incrementAndGet));
                doThrow(failure).when(change).toUserDataValue();
            })));
            assertTrue(cache.hasChangesToProcess());
            assertThrows(IllegalStateException.class, cache::processChangesForSharedRuntime);
            assertTrue(cache.hasChangesToProcess());
            doCallRealMethod().when(change).toUserDataValue();
            cache.setSharedStorageWriter(values -> { throw new IllegalStateException("storage failed"); });
            assertThrows(IllegalStateException.class, cache::processChangesForSharedRuntime);
            assertEquals(0, notifications.get());
            assertTrue(cache.hasChangesToProcess());
            runtime.flush(owner.uuid);
            assertEquals(1, notifications.get());
            assertEquals(12, runtime.read(owner.uuid, "Points", UserDataFetchMode.NO_CACHE, null,
                    new DataValueInt(0)).getInt());
            assertFalse(cache.hasChangesToProcess());
        }
    }

    private static final class Owner implements UserCacheOwner {
        final UUID uuid = UUID.randomUUID();
        final AdvancedCorePlugin plugin = mock(AdvancedCorePlugin.class);
        final UserDataManager manager = mock(UserDataManager.class);
        final UserManager users = mock(UserManager.class);
        final AdvancedCoreUser user = mock(AdvancedCoreUser.class);
        final UserData data = mock(UserData.class);
        final ConcurrentHashMap<UUID, UserDataCache> caches = new ConcurrentHashMap<>();
        final List<Runnable> notifications = new ArrayList<>();
        final List<Runnable> immediate = new ArrayList<>();
        SqlUserBackend backend;
        Consumer<Runnable> userGate, exclusiveGate;
        Owner() {
            when(plugin.getUserStorageOwnership()).thenReturn(new UserStorageOwnership());
            when(manager.getPlugin()).thenReturn(plugin);
            doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; })
                    .when(manager).dispatchSharedUserDataNotification(any(Runnable.class));
            when(manager.getTimer()).thenReturn(mock(ScheduledExecutorService.class));
            ScheduledExecutorService storageWorker = mock(ScheduledExecutorService.class);
            when(plugin.getTimer()).thenReturn(storageWorker);
            doAnswer(call -> { immediate.add(call.getArgument(0)); return null; }).when(storageWorker).execute(any(Runnable.class));
            when(manager.getUserDataCache()).thenReturn(caches);
            when(plugin.getUserManager()).thenReturn(users);
            when(users.getUser(uuid, false)).thenReturn(user);
            when(user.getUserData()).thenReturn(data);
        }
        UserDataCache cache() { return caches.get(uuid); }
        void bind(UserDataCache cache) {
            cache.configureSharedStorage(values -> backend.user(uuid).writeValues(backend.storageType(), values),
                    userGate, exclusiveGate);
        }
        @Override public void bindLifecycle(SqlUserBackend backend, Consumer<Runnable> lifecycle,
                BiConsumer<UUID, Runnable> user, BiConsumer<UUID, Runnable> exclusive) {
            this.backend = backend;
            userGate = operation -> user.accept(uuid, operation);
            exclusiveGate = operation -> exclusive.accept(uuid, operation);
        }
        @Override public void bindBackend(SqlUserBackend backend) { this.backend = backend; }
        @Override public boolean isCached(UUID id) { return caches.containsKey(id); }
        @Override public boolean hasPendingChanges(UUID id) { return cache() != null && cache().hasChangesToProcess(); }
        @Override public DataValue getIfPresent(UUID id, String key) { return cache() == null ? null : cache().getCachedValue(key); }
        @Override public void populate(UUID id, HashMap<String, DataValue> values) {
            UserDataCache cache = caches.computeIfAbsent(id, ignored -> new UserDataCache(manager, id));
            bind(cache);
            cache.updateCachePreservingPending(values);
        }
        @Override public void queueChange(UUID id, String key, DataValue value) {
            cache().addChange(new UserDataChangeInt(key, value.getInt()), true);
        }
        @Override public void flush(UUID id, SqlUserStorage storage) {
            UserDataCache cache = cache();
            if (cache == null) return;
            bind(cache);
            Runnable notification = cache.processChangesForSharedRuntime();
            if (notification != null) notifications.add(notification);
        }
        @Override public Set<UUID> cachedUsers() { return new HashSet<>(caches.keySet()); }
        @Override public void beginRemoval(UUID id) { if (cache() != null) cache().beginRemoval(); }
        @Override public void cancelRemoval(UUID id) { if (cache() != null) cache().cancelRemoval(); }
        @Override public void remove(UUID id) {
            UserDataCache cache = caches.remove(id);
            if (cache != null) cache.retireAfterSharedFlush();
        }
        @Override public void clearAfterFlush() { for (UUID id : cachedUsers()) remove(id); }
        @Override public void shutdown() { }
        @Override public void dispatchNotifications(UUID id) {
            List<Runnable> pending = new ArrayList<>(notifications);
            notifications.clear();
            pending.forEach(Runnable::run);
        }
        @Override public void dispatchAllNotifications() { dispatchNotifications(uuid); }
        @Override public void discardAllNotifications() { notifications.clear(); }
    }
}

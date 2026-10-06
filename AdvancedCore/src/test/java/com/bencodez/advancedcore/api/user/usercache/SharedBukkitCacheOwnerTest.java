package com.bencodez.advancedcore.api.user.usercache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.user.*;
import com.bencodez.advancedcore.api.user.usercache.change.UserDataChangeInt;
import com.bencodez.advancedcore.bukkit.user.runtime.BukkitUserCacheOwner;
import com.bencodez.advancedcore.core.user.runtime.*;
import com.bencodez.advancedcore.core.user.storage.sql.*;
import com.bencodez.simpleapi.sql.DataType;
import com.bencodez.simpleapi.sql.data.*;

/** Actual production owner/manager/cache/runtime and JDBC, with controlled plugin host hooks. */
@Timeout(15)
class SharedBukkitCacheOwnerTest {
    @TempDir Path directory;

    SqliteUserBackend backend(String name) {
        return new SqliteUserBackend(directory, name, "Users", SqlUserSchema.builder()
                .column("Points", "INTEGER", DataType.INTEGER)
                .column("Queue", "TEXT", DataType.STRING).build(), SqlBackendLogger.NO_OP);
    }

    @Test void actualOwnerFlushesNativeQueueAndClosesItsExistingManagerWorker() throws Exception {
        try (Host host = new Host(); SqliteUserBackend backend = backend("actual")) {
            SharedUserDataRuntime runtime = new SharedUserDataRuntime(backend, new BukkitUserCacheOwner(host.manager));
            assertTrue(host.manager.hasSharedSqlBackend());
            runtime.queueChange(host.uuid, "Points", new DataValueInt(4));
            assertEquals(4, host.cache().getCachedValue("Points").getInt());
            runtime.flush(host.uuid);
            host.barrier();
            assertEquals(4, host.manager.withSharedSqlBackend(host.uuid, (type, storage) -> storage.readRow(type))
                    .stream().filter(column -> column.getName().equals("Points")).findFirst().get().getValue().getInt());
            verify(host.users).onChange(eq(host.user), any(String[].class));
            verify(host.data, never()).setValuesStrict(any());
            runtime.queueChange(host.uuid, "Points", new DataValueInt(6));
            runtime.close();
            assertTrue(host.manager.getTimer().awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(host.manager.getUserDataCache().isEmpty());
            assertTrue(runtime.isClosed());
        }
        try (SqliteUserBackend reopened = backend("actual")) {
            assertEquals(6, reopened.enumerateUsers().stream().map(uuid -> reopened.user(uuid).readRow(UserStorage.SQLITE))
                    .flatMap(List::stream).filter(column -> column.getName().equals("Points"))
                    .findFirst().get().getValue().getInt());
        }
    }

    @Test void busyInitialBindingLeavesLegacyCacheAndAdmissionsUsable() throws Exception {
        try (Host host = new Host(); SqliteUserBackend backend = backend("busy")) {
            UserDataCache cache = new UserDataCache(host.manager, host.uuid);
            host.manager.getUserDataCache().put(host.uuid, cache);
            cache.addChange(new UserDataChangeInt("Points", 3), true);
            try (UserStorageOwnership.Scope work = host.plugin.getUserStorageOwnership().admit()) {
                assertThrows(IllegalStateException.class,
                        () -> new SharedUserDataRuntime(backend, new BukkitUserCacheOwner(host.manager)));
                assertFalse(host.manager.hasSharedSqlBackend());
                assertFalse(cache.hasSharedStorageBinding());
                cache.processChanges();
            }
            verify(host.data).setValuesStrict(any());
            assertFalse(cache.hasChangesToProcess());
            assertTrue(backend.isOpen());
            try (UserStorageOwnership.Scope later = host.plugin.getUserStorageOwnership().admit()) { }
        }
    }

    @Test void initialBindingPreservesAlreadyQueuedLegacyCachePayload() throws Exception {
        try (Host host = new Host(); SqliteUserBackend backend = backend("queued")) {
            UserDataCache cache = new UserDataCache(host.manager, host.uuid);
            host.manager.getUserDataCache().put(host.uuid, cache);
            cache.addChange(new UserDataChangeInt("Points", 8), true);
            try (SharedUserDataRuntime runtime = new SharedUserDataRuntime(backend, new BukkitUserCacheOwner(host.manager))) {
                assertTrue(cache.hasSharedStorageBinding());
                runtime.flush(host.uuid);
                assertEquals(8, runtime.read(host.uuid, "Points", UserDataFetchMode.NO_CACHE, null, new DataValueInt(0)).getInt());
                verify(host.data, never()).setValuesStrict(any());
            }
        }
    }

    @Test void replacementFencesQueuedPredecessorNotificationsAndRoutesTheSuccessorBackend() throws Exception {
        try (Host host = new Host(); SqliteUserBackend first = backend("first"); SqliteUserBackend second = backend("second")) {
            SharedUserDataRuntime runtime = new SharedUserDataRuntime(first, new BukkitUserCacheOwner(host.manager));
            CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicInteger notifications = new AtomicInteger();
            doAnswer(call -> { notifications.incrementAndGet(); return null; })
                    .when(host.users).onChange(eq(host.user), any(String[].class));
            host.manager.getTimer().execute(() -> {
                blocked.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            });
            try {
                assertTrue(blocked.await(5, TimeUnit.SECONDS));
                runtime.queueChange(host.uuid, "Points", new DataValueInt(2));
                runtime.flush(host.uuid);
                runtime.replaceBackend(second);
                assertFalse(first.isOpen());
                assertEquals(UserStorage.SQLITE, host.manager.effectiveStorageType(UserStorage.MYSQL));
                runtime.queueChange(host.uuid, "Points", new DataValueInt(9));
                runtime.flush(host.uuid);
                release.countDown();
                host.barrier();
                assertEquals(1, notifications.get());
                assertEquals(9, runtime.read(host.uuid, "Points", UserDataFetchMode.NO_CACHE, null, new DataValueInt(0)).getInt());
                runtime.close();
            } finally { release.countDown(); }
        }
    }

    @Test void wrongPopulationTokenAndConcurrentSnapshotReplacementCannotPublishStaleData() {
        try (Host host = new Host(); SqliteUserBackend backend = backend("population")) {
            BukkitUserCacheOwner owner = new BukkitUserCacheOwner(host.manager);
            try (SharedUserDataRuntime runtime = new SharedUserDataRuntime(backend, owner)) {
                UserCacheOwner.PopulationToken token = owner.beginPopulation(host.uuid);
                host.cache().updateCache(new HashMap<>(Collections.singletonMap("Points", new DataValueInt(11))));
                HashMap<String, DataValue> stale = new HashMap<>(Collections.singletonMap("Points", new DataValueInt(1)));
                assertEquals(11, owner.completePopulation(host.uuid, stale, token).get("Points").getInt());
                assertThrows(IllegalArgumentException.class,
                        () -> owner.completePopulation(UUID.randomUUID(), stale, token));
                assertThrows(IllegalArgumentException.class,
                        () -> owner.completePopulation(host.uuid, stale, new UserCacheOwner.PopulationToken() {}));
            }
        }
    }

    @Test void nativeCheckedDataApiUsesActiveSharedProviderAndRejectsWrongStore() throws Exception {
        try (Host host = new Host(); SqliteUserBackend backend = backend("native-api")) {
            try (SharedUserDataRuntime runtime = new SharedUserDataRuntime(backend, new BukkitUserCacheOwner(host.manager))) {
                UserData nativeData = new UserData(host.user);
                nativeData.setValuesStrict(Collections.singletonMap("Points", new DataValueInt(13)));
                assertEquals(13, nativeData.getValuesStrict().get("Points").getInt());
                assertEquals(13, nativeData.getSQLiteRow().stream().filter(column -> column.getName().equals("Points"))
                        .findFirst().get().getValue().getInt());
                assertThrows(IllegalStateException.class, nativeData::getMySqlRow);
                assertThrows(IllegalStateException.class,
                        () -> nativeData.setValuesStrict(UserStorage.MYSQL, Collections.singletonMap("Points", new DataValueInt(14))));
                assertEquals(13, nativeData.getValuesStrict().get("Points").getInt());
                verify(host.plugin, never()).getSQLiteUserTable();
                verify(host.plugin, never()).getMysql();
            }
        }
    }

    @Test void nativeDirectAndBulkWritesWaitForSharedTransactionThenUseTheCurrentCacheGeneration() throws Exception {
        try (Host host = new Host(); SqliteUserBackend backend = backend("native-write")) {
            try (SharedUserDataRuntime runtime = new SharedUserDataRuntime(backend, new BukkitUserCacheOwner(host.manager))) {
                UserData nativeData = new UserData(host.user);
                runtime.queueChange(host.uuid, "Points", new DataValueInt(2));
                CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
                ExecutorService workers = Executors.newFixedThreadPool(2);
                try {
                    Future<?> checkpoint = workers.submit(() -> runtime.transaction(host.uuid, scope -> {
                        entered.countDown();
                        try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                        scope.writeValues(Collections.singletonMap("Points", new DataValueInt(10)));
                        return null;
                    }));
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    Future<?> direct = workers.submit(() -> nativeData.setInt("Points", 20, false, false));
                    assertThrows(TimeoutException.class, () -> direct.get(100, TimeUnit.MILLISECONDS));
                    release.countDown();
                    checkpoint.get(5, TimeUnit.SECONDS);
                    direct.get(5, TimeUnit.SECONDS);
                    assertEquals(20, nativeData.getValuesStrict().get("Points").getInt());
                    UserDataCache cache = host.manager.getCache(host.uuid);
                    assertEquals(20, cache.getCachedValue("Points").getInt());
                    nativeData.setValues(new HashMap<>(Collections.singletonMap("Points", new DataValueInt(25))));
                    assertEquals(25, cache.getCachedValue("Points").getInt());
                    assertEquals(25, nativeData.getValuesStrict().get("Points").getInt());
                } finally {
                    release.countDown();
                    workers.shutdownNow();
                    assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
                }
            }
        }
    }

    @Test void strictQueueSnapshotUsesSharedStorageAndPreservesPendingCacheEdits() throws Exception {
        try (Host host = new Host(); SqliteUserBackend backend = backend("native-queue")) {
            try (SharedUserDataRuntime runtime = new SharedUserDataRuntime(backend, new BukkitUserCacheOwner(host.manager))) {
                UserData nativeData = new UserData(host.user);
                nativeData.setValuesStrict(Collections.singletonMap("Queue", new DataValueString("first%line%second")));
                assertEquals(Arrays.asList("first", "second"), nativeData.getStringListStrict("Queue"));
                runtime.queueChange(host.uuid, "Queue", new DataValueString("pending"));
                assertEquals(Collections.singletonList("pending"), nativeData.getStringListStrict("Queue"));
                assertEquals("first%line%second", nativeData.getValuesStrict().get("Queue").getString());
                runtime.flush(host.uuid);
                assertEquals("pending", nativeData.getValuesStrict().get("Queue").getString());
                verify(host.plugin, never()).getSQLiteUserTable();
                verify(host.plugin, never()).getMysql();
            }
        }
    }

    @Test void nativePresenceAndRemovalUseSharedProviderAndRetirePendingCacheGeneration() throws Exception {
        try (Host host = new Host(); SqliteUserBackend backend = backend("native-remove")) {
            try (SharedUserDataRuntime runtime = new SharedUserDataRuntime(backend, new BukkitUserCacheOwner(host.manager))) {
                UserData nativeData = new UserData(host.user);
                assertFalse(nativeData.hasData());
                nativeData.setValuesStrict(Collections.singletonMap("Points", new DataValueInt(13)));
                assertTrue(nativeData.hasData());
                runtime.queueChange(host.uuid, "Points", new DataValueInt(17));
                UserDataCache previous = host.cache();
                nativeData.remove();
                host.barrier();
                assertFalse(nativeData.hasData());
                assertNull(host.cache());
                assertNull(previous.getUuid());
                assertTrue(host.manager.getUserDataCache().isEmpty());
                assertTrue(backend.enumerateUsers().isEmpty());
                runtime.queueChange(host.uuid, "Points", new DataValueInt(19));
                assertNotSame(previous, host.cache());
                runtime.flush(host.uuid);
                assertEquals(19, nativeData.getValuesStrict().get("Points").getInt());
                verify(host.plugin, never()).getSQLiteUserTable();
                verify(host.plugin, never()).getMysql();
            }
        }
    }

    @Test void missingSharedPopulationSnapshotFailsWithoutPublishingEmptyReadyCache() {
        try (Host host = new Host()) {
            SqlUserBackend backend = mock(SqlUserBackend.class);
            com.bencodez.advancedcore.core.user.storage.SqlUserStorage storage =
                    mock(com.bencodez.advancedcore.core.user.storage.SqlUserStorage.class);
            when(backend.storageType()).thenReturn(UserStorage.SQLITE);
            when(backend.isOpen()).thenReturn(true);
            when(backend.user(host.uuid)).thenReturn(storage);
            when(storage.readRow(UserStorage.SQLITE)).thenReturn(null);
            try (SharedUserDataRuntime runtime = new SharedUserDataRuntime(backend, new BukkitUserCacheOwner(host.manager))) {
                assertThrows(IllegalStateException.class, () -> host.manager.getCache(host.uuid));
                assertFalse(host.cache().hasCache());
                when(storage.readRow(UserStorage.SQLITE)).thenReturn(Collections.singletonList(
                        new com.bencodez.simpleapi.sql.Column("Points", new DataValueInt(21))));
                assertEquals(21, host.manager.getCache(host.uuid).getCachedValue("Points").getInt());
            }
        }
    }

    @Test void replacementRejectsRetiredNativeHandlesAndUsesTheNewNativeGeneration() throws Exception {
        try (Host host = new Host(); SqliteUserBackend first = backend("captured-first");
                SqliteUserBackend second = backend("captured-second")) {
            try (SharedUserDataRuntime runtime = new SharedUserDataRuntime(first, new BukkitUserCacheOwner(host.manager))) {
                runtime.queueChange(host.uuid, "Points", new DataValueInt(4));
                runtime.flush(host.uuid);
                UserDataCache retained = host.cache();
                runtime.replaceBackend(second);
                assertFalse(first.isOpen());
                assertNull(retained.getUuid());
                assertThrows(IllegalStateException.class,
                        () -> retained.addChange(new UserDataChangeInt("Points", 9), true));
                UserDataCache successor = host.manager.getCache(host.uuid);
                assertNotSame(retained, successor);
                successor.addChange(new UserDataChangeInt("Points", 9), true);
                successor.processChanges();
                host.barrier();
                assertFalse(successor.hasChangesToProcess());
                assertEquals(9, second.user(host.uuid).readRow(UserStorage.SQLITE).stream()
                        .filter(column -> column.getName().equals("Points")).findFirst().get().getValue().getInt());
                verify(host.data, never()).setValuesStrict(any());
            }
        }
    }

    private static final class Host implements AutoCloseable {
        final UUID uuid = UUID.randomUUID();
        final AdvancedCorePlugin plugin = mock(AdvancedCorePlugin.class);
        final UserManager users = mock(UserManager.class);
        final AdvancedCoreUser user = mock(AdvancedCoreUser.class);
        final UserData data = mock(UserData.class);
        final UserDataManager manager;
        Host() {
            when(plugin.getUserStorageOwnership()).thenReturn(new UserStorageOwnership());
            when(plugin.getLogger()).thenReturn(Logger.getLogger("SharedBukkitCacheOwnerTest"));
            when(plugin.getUserManager()).thenReturn(users);
            when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
            when(users.getUser(uuid, false)).thenReturn(user);
            when(user.getUserData()).thenReturn(data);
            when(user.getPlugin()).thenReturn(plugin);
            when(user.getUUID()).thenReturn(uuid.toString());
            manager = new UserDataManager(plugin);
            when(users.getDataManager()).thenReturn(manager);
            when(plugin.getTimer()).thenReturn(manager.getTimer());
        }
        UserDataCache cache() { return manager.getUserDataCache().get(uuid); }
        void barrier() throws Exception { manager.getTimer().submit(() -> {}).get(5, TimeUnit.SECONDS); }
        @Override public void close() {
            manager.getTimer().shutdownNow();
            try { assertTrue(manager.getTimer().awaitTermination(5, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
        }
    }
}

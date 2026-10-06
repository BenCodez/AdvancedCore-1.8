package com.bencodez.advancedcore.api.user.usercache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.user.*;
import com.bencodez.advancedcore.api.user.usercache.keys.*;
import com.bencodez.advancedcore.api.user.userstorage.mysql.MySQL;
import com.bencodez.simpleapi.sql.Column;
import com.bencodez.simpleapi.sql.data.*;

class LegacyCanonicalStorageOwnerTest {
    @Test void twoUncachedWrappersCannotOverlapPhysicalWrites() throws Exception {
        Fixture f = new Fixture();
        AdvancedCoreUser other = mock(AdvancedCoreUser.class);
        when(other.getPlugin()).thenReturn(f.plugin); when(other.getUUID()).thenReturn(f.id.toString());
        UserData secondData = new UserData(other);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), attempted = new CountDownLatch(1);
        List<Integer> writes = Collections.synchronizedList(new ArrayList<>());
        doAnswer(call -> {
            List<Column> columns = call.getArgument(1); int value = columns.get(0).getValue().getInt();
            if (value == 7) { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); }
            writes.add(value); f.stored.set(value); return null;
        }).when(f.mysql).updateStrict(anyString(), anyList());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = workers.submit(() -> f.data.setInt("Points", 7, false));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Future<?> second = workers.submit(() -> { attempted.countDown(); secondData.setInt("Points", 9, false); });
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> second.get(100, TimeUnit.MILLISECONDS));
            assertTrue(writes.isEmpty()); release.countDown();
            first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
            assertEquals(Arrays.asList(7, 9), writes); assertEquals(9, f.stored.get());
        } finally { release.countDown(); workers.shutdownNow(); }
    }

    @Test void initialPopulationCannotPublishBeforeAnInterveningUncachedWrite() throws Exception {
        Fixture f = new Fixture();
        CountDownLatch prepared = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean paused = new AtomicBoolean();
        doAnswer(call -> {
            if (Thread.currentThread().getName().equals("private-population") && paused.compareAndSet(false, true)) {
                prepared.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            return f.keys;
        }).when(f.manager).getKeys();
        ExecutorService worker = Executors.newSingleThreadExecutor(task -> new Thread(task, "private-population"));
        try {
            Future<UserDataCache> candidate = worker.submit(() -> f.manager.getCache(f.id));
            assertTrue(prepared.await(5, TimeUnit.SECONDS));
            f.data.setInt("Points", 9, false); release.countDown();
            UserDataCache published = candidate.get(5, TimeUnit.SECONDS);
            assertEquals(9, published.getCachedValue("Points").getInt());
            assertSame(published, f.manager.getUserDataCache().get(f.id));
            verify(f.mysql, atLeast(2)).getExactStrict(f.id.toString());
        } finally { release.countDown(); worker.shutdownNow(); }
    }

    @Test void rawCheckedWriteFencesAnExistingCacheRefresh() throws Exception {
        Fixture f = new Fixture(); UserDataCache cache = f.manager.getCache(f.id);
        CountDownLatch prepared = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean paused = new AtomicBoolean();
        doAnswer(call -> {
            if (Thread.currentThread().getName().equals("snapshot-refresh") && paused.compareAndSet(false, true)) {
                prepared.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            return f.keys;
        }).when(f.manager).getKeys();
        ExecutorService worker = Executors.newSingleThreadExecutor(task -> new Thread(task, "snapshot-refresh"));
        try {
            Future<?> refreshing = worker.submit(cache::cache);
            assertTrue(prepared.await(5, TimeUnit.SECONDS));
            f.data.setValuesStrict(Collections.singletonMap("Points", new DataValueInt(11)));
            release.countDown(); refreshing.get(5, TimeUnit.SECONDS);
            assertEquals(11, cache.getCachedValue("Points").getInt());
        } finally { release.countDown(); worker.shutdownNow(); }
    }

    @Test void uncachedCommitCallbackMayAwaitPopulationOnAnotherThread() throws Exception {
        Fixture f = new Fixture(); ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            doAnswer(call -> {
                UserDataCache cache = worker.submit(() -> f.manager.getCache(f.id)).get(5, TimeUnit.SECONDS);
                assertEquals(9, cache.getCachedValue("Points").getInt()); return null;
            }).when(f.users).onChange(eq(f.user), any(String[].class));
            f.data.setInt("Points", 9, false);
            assertEquals(9, f.manager.getCache(f.id).getCachedValue("Points").getInt());
        } finally { worker.shutdownNow(); }
    }

    @Test void failedAndRecursiveWritesFenceSnapshotsWithoutPublishingSuccess() throws Exception {
        Fixture f = new Fixture(); UserStorageOwnership.Slot owner = f.owners.owner(f.id);
        doAnswer(call -> {
            assertThrows(IllegalStateException.class, () -> f.data.setInt("Points", 13, false));
            assertThrows(IllegalStateException.class, f.data::getValuesStrict);
            throw new java.sql.SQLException("unavailable");
        }).when(f.mysql).updateStrict(anyString(), anyList());
        assertThrows(IllegalStateException.class, () -> f.data.setInt("Points", 9, false));
        assertEquals(1, owner.getRevision()); verify(f.users, never()).onChange(any(), any());
        doAnswer(call -> { f.stored.set(7); return null; }).when(f.mysql).updateStrict(anyString(), anyList());
        f.data.setInt("Points", 7, false); assertEquals(2, owner.getRevision());
    }

    @Test void uncertainCommitFailureAlsoFencesAPrivateSnapshot() throws Exception {
        Fixture f = new Fixture();
        CountDownLatch prepared = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean paused = new AtomicBoolean();
        doAnswer(call -> {
            if (Thread.currentThread().getName().equals("uncertain-reader") && paused.compareAndSet(false, true)) {
                prepared.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            return f.keys;
        }).when(f.manager).getKeys();
        doAnswer(call -> { f.stored.set(9); throw new java.sql.SQLException("post-commit cleanup failure"); })
            .when(f.mysql).updateStrict(anyString(), anyList());
        ExecutorService worker = Executors.newSingleThreadExecutor(task -> new Thread(task, "uncertain-reader"));
        try {
            Future<UserDataCache> reading = worker.submit(() -> f.manager.getCache(f.id));
            assertTrue(prepared.await(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> f.data.setInt("Points", 9, false));
            verify(f.users, never()).onChange(any(), any());
            release.countDown(); assertEquals(9, reading.get(5, TimeUnit.SECONDS).getCachedValue("Points").getInt());
        } finally { release.countDown(); worker.shutdownNow(); }
    }

    @Test void ownershipRemainsBoundedAndStableWithoutCreatingCacheEntries() {
        Fixture f = new Fixture(); Set<UserStorageOwnership.Slot> slots = new HashSet<>();
        for (int i = 0; i < 10000; i++) slots.add(f.owners.owner(new UUID(0, i)));
        assertEquals(64, slots.size());
        assertSame(f.owners.owner(f.id), f.owners.owner(UUID.fromString(f.id.toString())));
        assertTrue(f.manager.getUserDataCache().isEmpty());
    }

    private static class Fixture {
        final UUID id = UUID.randomUUID(); final AtomicInteger stored = new AtomicInteger(3);
        final AdvancedCorePlugin plugin = mock(AdvancedCorePlugin.class);
        final UserManager users = mock(UserManager.class); final AdvancedCoreUser user = mock(AdvancedCoreUser.class);
        final MySQL mysql = mock(MySQL.class); final UserStorageOwnership owners = new UserStorageOwnership();
        final UserDataManager manager = mock(UserDataManager.class, CALLS_REAL_METHODS);
        final UserData data = new UserData(user);
        final ArrayList<UserDataKey> keys = new ArrayList<>(Collections.singletonList(new UserDataKeyInt("Points")));
        Fixture() {
            try {
                set("plugin", plugin); set("userDataCache", new ConcurrentHashMap<UUID, UserDataCache>());
                set("keys", keys); set("timer", mock(ScheduledExecutorService.class));
                when(plugin.getUserStorageOwnership()).thenReturn(owners); when(plugin.getUserManager()).thenReturn(users);
                when(plugin.getStorageType()).thenReturn(UserStorage.MYSQL); when(plugin.getMysql()).thenReturn(mysql);
                when(users.getDataManager()).thenReturn(manager); when(users.getUser(any(UUID.class), eq(false))).thenReturn(user);
                when(user.getPlugin()).thenReturn(plugin); when(user.getUUID()).thenReturn(id.toString()); when(user.getUserData()).thenReturn(data);
                when(mysql.getExactStrict(anyString())).thenAnswer(call -> new ArrayList<>(Collections.singletonList(new Column("Points", new DataValueInt(stored.get())))));
                doAnswer(call -> { List<Column> columns = call.getArgument(1); stored.set(columns.get(0).getValue().getInt()); return null; })
                    .when(mysql).updateStrict(anyString(), anyList());
            } catch (Exception failure) { throw new AssertionError(failure); }
        }
        void set(String name, Object value) throws Exception { Field field = UserDataManager.class.getDeclaredField(name); field.setAccessible(true); field.set(manager, value); }
    }
}

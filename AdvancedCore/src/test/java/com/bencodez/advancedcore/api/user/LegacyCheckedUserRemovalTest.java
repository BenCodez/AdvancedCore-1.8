package com.bencodez.advancedcore.api.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.sql.SQLException;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.api.user.usercache.*;
import com.bencodez.advancedcore.api.user.usercache.change.UserDataChangeInt;
import com.bencodez.advancedcore.api.user.userstorage.sql.UserTable;

class LegacyCheckedUserRemovalTest {
    @Test void olderPendingWriteCommitsBeforeDeletionAndRetiredQueueCannotRecreateRow() throws Exception {
        LegacyDirectUserDataTest.Fixture f = new LegacyDirectUserDataTest.Fixture(); List<String> order = new ArrayList<>();
        doAnswer(call -> { order.add("flush"); return null; }).when(f.mysql).updateStrict(anyString(), anyList());
        doAnswer(call -> { order.add("delete"); assertThrows(IllegalStateException.class,
            () -> f.cache.addChange(new UserDataChangeInt("Points", 9), true)); return null; }).when(f.mysql).deletePlayerStrict(anyString());
        f.cache.addChange(new UserDataChangeInt("Points", 2), true); f.data.remove();
        assertEquals(Arrays.asList("flush", "delete"), order); assertTrue(f.manager.getUserDataCache().isEmpty());
        assertNull(f.cache.getUuid()); assertFalse(f.cache.hasChangesToProcess()); f.cache.processChanges();
        assertEquals(2, order.size()); verify(f.user, never()).clearCache(); verify(f.mysql, never()).deletePlayer(anyString());
        verify(f.users).onChange(eq(f.user), any(String[].class));
    }
    @Test void failedOlderFlushPreventsDeletionAndRetainsPendingGeneration() throws Exception {
        LegacyDirectUserDataTest.Fixture f = new LegacyDirectUserDataTest.Fixture(); UUID identity = f.cache.getUuid();
        f.cache.addChange(new UserDataChangeInt("Points", 2), true);
        doThrow(new SQLException("offline")).when(f.mysql).updateStrict(anyString(), anyList());
        assertThrows(IllegalStateException.class, f.data::remove); verify(f.mysql, never()).deletePlayerStrict(anyString());
        assertSame(f.cache, f.manager.getUserDataCache().get(identity)); assertTrue(f.cache.hasChangesToProcess());
        f.cache.addChange(new UserDataChangeInt("Points", 3), true); assertEquals(3, f.cache.getCachedValue("Points").getInt());
    }
    @Test void failedDeleteRetainsLiveCacheButDoesNotRetryItsAcknowledgedOlderPrefix() throws Exception {
        LegacyDirectUserDataTest.Fixture f = new LegacyDirectUserDataTest.Fixture(); UUID identity = f.cache.getUuid();
        f.cache.addChange(new UserDataChangeInt("Points", 2), true); IllegalStateException offline = new IllegalStateException("offline");
        doThrow(offline).when(f.mysql).deletePlayerStrict(anyString());
        assertSame(offline, assertThrows(IllegalStateException.class, f.data::remove));
        assertSame(f.cache, f.manager.getUserDataCache().get(identity)); assertFalse(f.cache.hasChangesToProcess());
        assertEquals(2, f.cache.getCachedValue("Points").getInt()); f.cache.processChanges();
        verify(f.mysql).updateStrict(anyString(), anyList()); verify(f.users).onChange(eq(f.user), any(String[].class));
        f.cache.addChange(new UserDataChangeInt("Points", 3), true);
    }
    @Test void failedPrefixNotificationAfterDeleteCarriesCommittedRemovalMarker() throws Exception {
        LegacyDirectUserDataTest.Fixture f = new LegacyDirectUserDataTest.Fixture(); f.cache.addChange(new UserDataChangeInt("Points", 2), true);
        IllegalStateException listener = new IllegalStateException("listener failed"); doThrow(listener).when(f.users).onChange(eq(f.user), any(String[].class));
        CommittedUserDataRemovalException failure = assertThrows(CommittedUserDataRemovalException.class, f.data::remove);
        assertSame(listener, failure.getCause()); assertTrue(f.manager.getUserDataCache().isEmpty()); assertNull(f.cache.getUuid());
        verify(f.mysql).deletePlayerStrict(anyString());
    }
    @Test void primaryDeletionFailureKeepsNotificationFailureSuppressed() throws Exception {
        LegacyDirectUserDataTest.Fixture f = new LegacyDirectUserDataTest.Fixture(); f.cache.addChange(new UserDataChangeInt("Points", 2), true);
        IllegalStateException storage = new IllegalStateException("delete failed"), listener = new IllegalStateException("listener failed");
        doThrow(storage).when(f.mysql).deletePlayerStrict(anyString()); doThrow(listener).when(f.users).onChange(eq(f.user), any(String[].class));
        assertSame(storage, assertThrows(IllegalStateException.class, f.data::remove)); assertArrayEquals(new Throwable[] {listener}, storage.getSuppressed());
        assertNotNull(f.cache.getUuid());
    }
    @Test void oldPrefixCallbackRunsAfterRegistryRemovalAndCanAwaitAnotherWriter() throws Exception {
        LegacyDirectUserDataTest.Fixture f = new LegacyDirectUserDataTest.Fixture(); f.cache.addChange(new UserDataChangeInt("Points", 2), true);
        ExecutorService worker = Executors.newSingleThreadExecutor(); java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(true);
        try {
            doAnswer(call -> { if (first.compareAndSet(true, false)) {
                assertTrue(f.manager.getUserDataCache().isEmpty()); worker.submit(() -> f.data.setInt("Points", 9, false)).get(5, TimeUnit.SECONDS);
            } return null; }).when(f.users).onChange(eq(f.user), any(String[].class));
            f.data.remove(); verify(f.mysql).deletePlayerStrict(anyString()); verify(f.mysql, times(2)).updateStrict(anyString(), anyList());
        } finally { worker.shutdownNow(); }
    }
    @Test void uncachedDeletionUsesCheckedGatewayWithoutCreatingCacheOrCallbacks() {
        LegacyDirectUserDataTest.Fixture f = new LegacyDirectUserDataTest.Fixture(); f.manager.getUserDataCache().clear(); f.data.remove();
        verify(f.mysql).deletePlayerStrict(f.user.getUUID()); assertTrue(f.manager.getUserDataCache().isEmpty()); verify(f.users, never()).onChange(any(), any());
    }
    @Test void recursiveUncachedDeletionIsRejectedBeforeSecondPhysicalDelete() {
        LegacyDirectUserDataTest.Fixture f = new LegacyDirectUserDataTest.Fixture(); f.manager.getUserDataCache().clear();
        doAnswer(call -> { assertThrows(IllegalStateException.class, f.data::remove); return null; }).when(f.mysql).deletePlayerStrict(anyString());
        f.data.remove(); verify(f.mysql).deletePlayerStrict(anyString());
    }
    @Test void sqliteFailureCannotRetireTheActiveGeneration() throws Exception {
        LegacyDirectUserDataTest.Fixture f = new LegacyDirectUserDataTest.Fixture(); when(f.plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        UserTable table = mock(UserTable.class); when(f.plugin.getSQLiteUserTable()).thenReturn(table); SQLException offline = new SQLException("offline");
        doThrow(offline).when(table).deleteStrict(any());
        assertSame(offline, assertThrows(IllegalStateException.class, f.data::remove).getCause()); assertNotNull(f.cache.getUuid());
        verify(table, never()).delete(any());
    }
    @Test void anotherWriterWaitsForDeletionAndWritesThroughTheNewUncachedGeneration() throws Exception {
        LegacyDirectUserDataTest.Fixture f = new LegacyDirectUserDataTest.Fixture(); List<String> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch deleting = new CountDownLatch(1), release = new CountDownLatch(1), writing = new CountDownLatch(1);
        doAnswer(call -> { deleting.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); order.add("delete"); return null; }).when(f.mysql).deletePlayerStrict(anyString());
        doAnswer(call -> { order.add("write"); writing.countDown(); return null; }).when(f.mysql).updateStrict(anyString(), anyList());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> deletion = workers.submit(f.data::remove); assertTrue(deleting.await(5, TimeUnit.SECONDS));
            Future<?> write = workers.submit(() -> f.data.setInt("Points", 9, false)); assertFalse(writing.await(150, TimeUnit.MILLISECONDS));
            release.countDown(); deletion.get(5, TimeUnit.SECONDS); write.get(5, TimeUnit.SECONDS);
            assertEquals(Arrays.asList("delete", "write"), order); assertNull(f.cache.getUuid()); assertTrue(f.manager.getUserDataCache().isEmpty());
        } finally { release.countDown(); workers.shutdownNow(); }
    }
    private void setPlugin(LegacyDirectUserDataTest.Fixture f) {
        try { java.lang.reflect.Field field = UserManager.class.getDeclaredField("plugin"); field.setAccessible(true); field.set(f.users, f.plugin); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    @Test void removeUuidRoutesSqlThroughTheSameOwnedRemovalButRetainsFlatNoOpScope() {
        LegacyDirectUserDataTest.Fixture f = new LegacyDirectUserDataTest.Fixture(); UUID identity = f.cache.getUuid();
        when(f.users.getUser(identity, false)).thenReturn(f.user); setPlugin(f);
        doCallRealMethod().when(f.users).removeUUID(identity); f.users.removeUUID(identity);
        verify(f.mysql).deletePlayerStrict(identity.toString()); assertTrue(f.manager.getUserDataCache().isEmpty());
        when(f.plugin.getStorageType()).thenReturn(UserStorage.FLAT); clearInvocations(f.mysql, f.users); f.users.removeUUID(identity);
        verify(f.users, never()).getUser(any(UUID.class), anyBoolean()); verifyNoInteractions(f.mysql);
    }
}

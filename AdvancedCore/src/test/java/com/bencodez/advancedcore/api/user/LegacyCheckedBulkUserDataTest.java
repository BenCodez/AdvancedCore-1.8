package com.bencodez.advancedcore.api.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import com.bencodez.advancedcore.thread.FileThread;
import org.bukkit.configuration.file.YamlConfiguration;
import com.bencodez.advancedcore.api.user.usercache.CommittedUserDataBatchException;
import com.bencodez.advancedcore.api.user.usercache.change.UserDataChangeInt;
import com.bencodez.advancedcore.api.user.userstorage.sql.UserTable;
import com.bencodez.simpleapi.sql.Column;
import com.bencodez.simpleapi.sql.data.*;

class LegacyCheckedBulkUserDataTest {
    private LegacyDirectUserDataTest.Fixture fixture() {
        LegacyDirectUserDataTest.Fixture f = new LegacyDirectUserDataTest.Fixture();
        HashMap<String, DataValue> old = new HashMap<>(); old.put("Points", new DataValueInt(1));
        old.put("Label", new DataValueString("old")); old.put("Enabled", new DataValueBoolean(false));
        f.cache.updateCache(old); return f;
    }
    private HashMap<String, DataValue> candidate() {
        HashMap<String, DataValue> result = new HashMap<>(); result.put("Points", new DataValueInt(7));
        result.put("Label", new DataValueString("new")); result.put("Enabled", new DataValueBoolean(true)); return result;
    }
    private DataValue value(List<Column> columns, String key) {
        return columns.stream().filter(c -> c.getName().equals(key)).findFirst().get().getValue();
    }

    @Test void oneCheckedBatchPublishesEveryFieldAfterAcknowledgementWithoutNewBulkCallbacks() throws Exception {
        LegacyDirectUserDataTest.Fixture f = fixture(); HashMap<String, DataValue> proposed = candidate();
        proposed.put("uuid", new DataValueString("ignored"));
        doAnswer(call -> {
            List<Column> columns = call.getArgument(1); assertEquals(3, columns.size());
            assertEquals(7, value(columns, "Points").getInt()); assertEquals("new", value(columns, "Label").getString());
            assertTrue(value(columns, "Enabled").getBoolean());
            assertEquals(1, f.cache.getCachedValue("Points").getInt()); assertEquals("old", f.cache.getCachedValue("Label").getString());
            assertFalse(f.cache.getCachedValue("Enabled").getBoolean()); proposed.clear(); return null;
        }).when(f.mysql).updateStrict(anyString(), anyList());
        f.data.setValues(proposed);
        assertEquals(7, f.cache.getCachedValue("Points").getInt()); assertEquals("new", f.cache.getCachedValue("Label").getString());
        assertTrue(f.cache.getCachedValue("Enabled").getBoolean()); assertNull(f.cache.getCachedValue("uuid"));
        verify(f.mysql).updateStrict(anyString(), anyList()); verify(f.mysql, never()).update(anyString(), anyList(), anyBoolean());
        verify(f.users, never()).onChange(any(), any());
    }

    @Test void failedBatchPreservesAllPredecessorFieldsAndExposesFailure() throws Exception {
        LegacyDirectUserDataTest.Fixture f = fixture(); SQLException offline = new SQLException("offline");
        doThrow(offline).when(f.mysql).updateStrict(anyString(), anyList());
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> f.data.setValues(candidate()));
        assertSame(offline, failure.getCause()); assertEquals(1, f.cache.getCachedValue("Points").getInt());
        assertEquals("old", f.cache.getCachedValue("Label").getString()); assertFalse(f.cache.getCachedValue("Enabled").getBoolean());
        verify(f.users, never()).onChange(any(), any());
    }

    @Test void olderQueuedWriteCommitsFirstAndNewerQueuedValueStaysVisible() throws Exception {
        LegacyDirectUserDataTest.Fixture f = fixture(); List<Integer> writes = new ArrayList<>();
        doAnswer(call -> { List<Column> columns = call.getArgument(1); int next = value(columns, "Points").getInt(); writes.add(next);
            if (next == 2) f.cache.addChange(new UserDataChangeInt("Points", 9), true); return null;
        }).when(f.mysql).updateStrict(anyString(), anyList());
        f.cache.addChange(new UserDataChangeInt("Points", 2), true); f.data.setValues(candidate());
        assertEquals(Arrays.asList(2, 7), writes); assertEquals(9, f.cache.getCachedValue("Points").getInt());
        assertEquals("new", f.cache.getCachedValue("Label").getString()); assertTrue(f.cache.hasChangesToProcess());
        f.cache.processChanges(); assertEquals(Arrays.asList(2, 7, 9), writes);
    }

    @Test void queuedValueArrivingDuringBulkWriteDoesNotReplaceOtherCommittedFields() throws Exception {
        LegacyDirectUserDataTest.Fixture f = fixture();
        doAnswer(call -> { f.cache.addChange(new UserDataChangeInt("Points", 11), true); return null; })
            .when(f.mysql).updateStrict(anyString(), anyList());
        f.data.setValues(candidate()); assertEquals(11, f.cache.getCachedValue("Points").getInt());
        assertEquals("new", f.cache.getCachedValue("Label").getString()); assertTrue(f.cache.getCachedValue("Enabled").getBoolean());
        assertTrue(f.cache.hasChangesToProcess());
    }

    @Test void failedOlderFlushRetainsPendingChangesAndNeverWritesTheNewBatch() throws Exception {
        LegacyDirectUserDataTest.Fixture f = fixture(); UserDataChangeInt old = spy(new UserDataChangeInt("Points", 2));
        f.cache.addChange(old, true); doThrow(new SQLException("offline")).when(f.mysql).updateStrict(anyString(), anyList());
        assertThrows(IllegalStateException.class, () -> f.data.setValues(candidate()));
        verify(f.mysql).updateStrict(anyString(), anyList()); verify(old, never()).dump();
        assertTrue(f.cache.hasChangesToProcess()); assertEquals(2, f.cache.getCachedValue("Points").getInt());
        assertEquals("old", f.cache.getCachedValue("Label").getString());
    }

    @Test void committedBatchRemainsDistinguishableWhenOldPrefixNotificationFails() throws Exception {
        LegacyDirectUserDataTest.Fixture f = fixture(); f.cache.addChange(new UserDataChangeInt("Points", 2), true);
        IllegalStateException callback = new IllegalStateException("listener failed");
        doThrow(callback).when(f.users).onChange(eq(f.user), any(String[].class));
        CommittedUserDataBatchException failure = assertThrows(CommittedUserDataBatchException.class, () -> f.data.setValues(candidate()));
        assertSame(callback, failure.getCause()); assertEquals(7, failure.getCommittedValues().get("Points").getInt());
        assertThrows(UnsupportedOperationException.class, () -> failure.getCommittedValues().clear());
        assertEquals(7, f.cache.getCachedValue("Points").getInt()); assertFalse(f.cache.hasChangesToProcess());
        verify(f.mysql, times(2)).updateStrict(anyString(), anyList()); verify(f.users).onChange(eq(f.user), any(String[].class));
    }

    @Test void oldPrefixNotificationFailureCannotHideANewerStorageFailure() throws Exception {
        LegacyDirectUserDataTest.Fixture f = fixture(); f.cache.addChange(new UserDataChangeInt("Points", 2), true);
        SQLException offline = new SQLException("second write unavailable");
        doNothing().doThrow(offline).when(f.mysql).updateStrict(anyString(), anyList());
        IllegalStateException callback = new IllegalStateException("listener failed");
        doThrow(callback).when(f.users).onChange(eq(f.user), any(String[].class));
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> f.data.setValues(candidate()));
        assertFalse(failure instanceof CommittedUserDataBatchException); assertSame(offline, failure.getCause());
        assertArrayEquals(new Throwable[] { callback }, failure.getSuppressed());
        assertEquals(2, f.cache.getCachedValue("Points").getInt()); assertEquals("old", f.cache.getCachedValue("Label").getString());
    }

    @Test void explicitAlternateStorageDoesNotFlushOrPublishTheActiveStoreCache() throws Exception {
        LegacyDirectUserDataTest.Fixture f = fixture(); UserTable sqlite = mock(UserTable.class);
        when(f.plugin.getSQLiteUserTable()).thenReturn(sqlite); f.cache.addChange(new UserDataChangeInt("Points", 2), true);
        f.data.setValues(UserStorage.SQLITE, candidate());
        verify(sqlite).updateStrict(any(), anyList()); verify(f.mysql, never()).updateStrict(anyString(), anyList());
        assertEquals(2, f.cache.getCachedValue("Points").getInt()); assertEquals("old", f.cache.getCachedValue("Label").getString());
        assertTrue(f.cache.hasChangesToProcess()); verify(f.users, never()).onChange(any(), any());
    }

    @Test void emptyAndIgnoredSqlIdentityRemainNoOpsWithoutAvailableStorage() {
        LegacyDirectUserDataTest.Fixture f = fixture(); when(f.plugin.getMysql()).thenReturn(null);
        f.data.setValues(new HashMap<>()); f.data.setValues("uuid", null); f.data.setValues(null, new HashMap<>());
        assertEquals(1, f.cache.getCachedValue("Points").getInt()); verify(f.users, never()).onChange(any(), any());
    }

    @Test void uncachedBulkWriteDoesNotCreateACacheOrAddLegacyNotifications() throws Exception {
        LegacyDirectUserDataTest.Fixture f = fixture(); f.manager.getUserDataCache().clear();
        f.data.setValues(candidate()); verify(f.mysql).updateStrict(anyString(), anyList());
        assertTrue(f.manager.getUserDataCache().isEmpty()); assertEquals(1, f.cache.getCachedValue("Points").getInt());
        verify(f.users, never()).onChange(any(), any());
    }

    @Test void olderNotificationCanAwaitAnotherThreadsWriteAfterTheBulkCommit() throws Exception {
        LegacyDirectUserDataTest.Fixture f = fixture(); f.cache.addChange(new UserDataChangeInt("Points", 2), true);
        ExecutorService worker = Executors.newSingleThreadExecutor(); AtomicBoolean first = new AtomicBoolean(true);
        try {
            doAnswer(call -> {
                if (first.compareAndSet(true, false)) {
                    assertEquals(7, f.cache.getCachedValue("Points").getInt());
                    worker.submit(() -> f.data.setInt("Points", 9, false)).get(5, TimeUnit.SECONDS);
                }
                return null;
            }).when(f.users).onChange(eq(f.user), any(String[].class));
            f.data.setValues(candidate()); assertEquals(9, f.cache.getCachedValue("Points").getInt());
            assertEquals("new", f.cache.getCachedValue("Label").getString());
            verify(f.mysql, times(3)).updateStrict(anyString(), anyList());
        } finally { worker.shutdownNow(); }
    }

    @TempDir Path directory;

    private FileThread fileOwner(LegacyDirectUserDataTest.Fixture f) throws Exception {
        FileThread owner = mock(FileThread.class, CALLS_REAL_METHODS);
        when(f.plugin.getDataFolder()).thenReturn(directory.toFile());
        java.lang.reflect.Field field = FileThread.class.getDeclaredField("plugin");
        field.setAccessible(true); field.set(owner, f.plugin); return owner;
    }

    @Test void flatBulkEntryWritesTypedBatchAndPublishesMatchingCacheWithoutStartingPoller() throws Exception {
        LegacyDirectUserDataTest.Fixture f = fixture(); when(f.plugin.getStorageType()).thenReturn(UserStorage.FLAT);
        FileThread owner = fileOwner(f); HashMap<String, DataValue> values = candidate();
        values.put("uuid", new DataValueString("legacy-field"));
        try (org.mockito.MockedStatic<FileThread> singleton = mockStatic(FileThread.class)) {
            singleton.when(FileThread::getInstance).thenReturn(owner);
            f.data.setValues(values);
        }
        Path file = directory.resolve("Data").resolve(f.user.getUUID() + ".yml");
        YamlConfiguration yaml = new YamlConfiguration(); yaml.load(file.toFile());
        assertEquals(7, yaml.getInt("Points")); assertEquals("new", yaml.getString("Label"));
        assertEquals("true", yaml.getString("Enabled")); assertEquals("legacy-field", yaml.getString("uuid"));
        assertEquals(7, f.cache.getCachedValue("Points").getInt()); assertTrue(f.cache.getCachedValue("Enabled").getBoolean());
        verify(owner, never()).getThread(); verify(f.users, never()).onChange(any(), any());
    }

    @Test void malformedFlatPredecessorRejectsBulkWithoutChangingFileOrCache() throws Exception {
        LegacyDirectUserDataTest.Fixture f = fixture(); when(f.plugin.getStorageType()).thenReturn(UserStorage.FLAT);
        FileThread owner = fileOwner(f); Path file = directory.resolve("Data").resolve(f.user.getUUID() + ".yml");
        Files.createDirectories(file.getParent()); byte[] bad = "Points: [\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file, bad);
        try (org.mockito.MockedStatic<FileThread> singleton = mockStatic(FileThread.class)) {
            singleton.when(FileThread::getInstance).thenReturn(owner);
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> f.data.setValues(candidate()));
            assertTrue(failure.getCause() instanceof java.io.IOException);
        }
        assertArrayEquals(bad, Files.readAllBytes(file)); assertEquals(1, f.cache.getCachedValue("Points").getInt());
        assertEquals("old", f.cache.getCachedValue("Label").getString()); assertFalse(f.cache.getCachedValue("Enabled").getBoolean());
        verify(owner, never()).getThread(); verify(f.users, never()).onChange(any(), any());
    }

    @Test void invalidProposalIsRejectedBeforeFlushingAnyOlderPendingWork() throws Exception {
        LegacyDirectUserDataTest.Fixture f = fixture(); f.cache.addChange(new UserDataChangeInt("Points", 2), true);
        HashMap<String, DataValue> invalid = candidate(); invalid.put("Label", null);
        assertThrows(NullPointerException.class, () -> f.data.setValues(invalid));
        verify(f.mysql, never()).updateStrict(anyString(), anyList()); assertTrue(f.cache.hasChangesToProcess());
        assertEquals("old", f.cache.getCachedValue("Label").getString());
    }
}

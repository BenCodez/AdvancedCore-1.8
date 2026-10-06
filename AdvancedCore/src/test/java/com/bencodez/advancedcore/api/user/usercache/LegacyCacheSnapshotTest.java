package com.bencodez.advancedcore.api.user.usercache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.user.*;
import com.bencodez.advancedcore.api.user.usercache.change.UserDataChangeString;
import com.bencodez.advancedcore.api.user.usercache.keys.UserDataKeyString;
import com.bencodez.simpleapi.sql.data.*;

class LegacyCacheSnapshotTest {
    @Test void refreshPreservesQueuedValueAndLoadsDynamicStoredKeys() throws Exception {
        Fixture f=new Fixture();f.cache.addChange(new UserDataChangeString("Points","new"),true);
        when(f.data.getValuesStrict()).thenReturn(values("old"));f.cache.cache();
        assertEquals("new",f.value());assertEquals("keep",f.cache.getCache().get("Dynamic").getString());
        assertTrue(f.cache.hasChangesToProcess());
    }
    @Test void explicitReplacementCopiesInputAndPreservesQueuedChanges() {
        Fixture f=new Fixture();f.cache.addChange(new UserDataChangeString("Points","new"),true);
        HashMap<String,DataValue> snapshot=values("old");f.cache.updateCache(snapshot);snapshot.clear();
        assertEquals("new",f.value());assertEquals("keep",f.cache.getCache().get("Dynamic").getString());
    }
    @Test void replacementDuringClaimedWriteKeepsThePendingValueVisible() throws Exception {
        Fixture f=new Fixture();doAnswer(call->{f.cache.updateCache(values("stale"));assertEquals("new",f.value());return null;})
            .when(f.data).setValuesStrict(any());
        f.cache.addChange(new UserDataChangeString("Points","new"),true);f.cache.processChanges();assertEquals("new",f.value());
    }
    @Test void readStartedBeforeAWriteCannotOverwriteItsCommittedValue() throws Exception {
        Fixture f=new Fixture();BlockingRead read=new BlockingRead(f);ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<?> refresh=worker.submit(f.cache::cache);assertTrue(read.entered.await(5,TimeUnit.SECONDS));
            f.cache.addChange(new UserDataChangeString("Points","new"),true);f.cache.processChanges();
            read.release.countDown();refresh.get(5,TimeUnit.SECONDS);assertEquals("new",f.value());assertFalse(f.cache.hasChangesToProcess());
        } finally {read.release.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void lateReadCannotUndoExplicitReplacement() throws Exception {
        Fixture f=new Fixture();BlockingRead read=new BlockingRead(f);ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<?> refresh=worker.submit(f.cache::cache);assertTrue(read.entered.await(5,TimeUnit.SECONDS));
            f.cache.updateCache(values("replacement"));read.release.countDown();refresh.get(5,TimeUnit.SECONDS);
            assertEquals("replacement",f.value());
        } finally {read.release.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void lateReadCannotResurrectClearedOrRetiredCache() throws Exception {
        for(boolean retire:new boolean[]{false,true}) {
            Fixture f=new Fixture();BlockingRead read=new BlockingRead(f);ExecutorService worker=Executors.newSingleThreadExecutor();
            try {
                Future<?> refresh=worker.submit(f.cache::cache);assertTrue(read.entered.await(5,TimeUnit.SECONDS));
                if(retire)f.cache.dump();else f.cache.clearCache();
                read.release.countDown();refresh.get(5,TimeUnit.SECONDS);assertFalse(f.cache.hasCache());
                if(retire){f.cache.updateCache(values("resurrected"));assertNull(f.cache.getCache());}
            } finally {read.release.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
        }
    }
    @Test void storageReadDoesNotHoldTheCacheMonitor() throws Exception {
        Fixture f=new Fixture();ExecutorService writer=Executors.newSingleThreadExecutor();
        try {
            doAnswer(call->{writer.submit(()->f.cache.addChange(new UserDataChangeString("Points","new"),true))
                .get(5,TimeUnit.SECONDS);return values("old");}).when(f.data).getValuesStrict();
            f.cache.cache();assertEquals("new",f.value());
        } finally {writer.shutdownNow();assertTrue(writer.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void nullReplacementStillPreservesPendingDataAndLaterRefreshCanApplyStoredValues() throws Exception {
        Fixture f=new Fixture();f.cache.addChange(new UserDataChangeString("Points","new"),true);
        f.cache.updateCache(null);assertEquals("new",f.value());f.cache.processChanges();
        when(f.data.getValuesStrict()).thenReturn(values("stored"));f.cache.cache();assertEquals("stored",f.value());
    }
    @Test void writeQueuedBeforeReadStillFencesItsLaterCompletion() throws Exception {
        Fixture f=new Fixture();f.cache.addChange(new UserDataChangeString("Points","new"),true);
        BlockingRead read=new BlockingRead(f);ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<?> refresh=worker.submit(f.cache::cache);assertTrue(read.entered.await(5,TimeUnit.SECONDS));
            f.cache.processChanges();read.release.countDown();refresh.get(5,TimeUnit.SECONDS);assertEquals("new",f.value());
        } finally {read.release.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void typedUserDataReadsUseOneCoherentCacheLookupAndRetiredReadsFallBack() {
        Fixture f=new Fixture();when(f.user.getPlugin()).thenReturn(f.plugin);when(f.user.getCache()).thenReturn(f.cache);
        when(f.plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        f.cache.updateCache(values("43"));doThrow(new AssertionError("raw mutable map access")).when(f.cache).getCache();
        UserData reader=new UserData(f.user);assertEquals(43,reader.getInt("Points",true,true));
        assertEquals("43",reader.getString("Points",true,true));
        f.cache.dump();com.bencodez.advancedcore.api.user.userstorage.sql.UserTable table=
            mock(com.bencodez.advancedcore.api.user.userstorage.sql.UserTable.class);
        when(f.plugin.getSQLiteUserTable()).thenReturn(table);
        when(table.getExact(any())).thenReturn(new ArrayList<>(Collections.singletonList(
            new com.bencodez.simpleapi.sql.Column("Points",new DataValueString("52")))));
        assertEquals(52,reader.getInt("Points",true,true));assertEquals("52",reader.getString("Points",true,true));
    }
    @Test void failedCheckedReadLeavesExistingCacheAndPendingPayloadUnchanged() throws Exception {
        Fixture f=new Fixture();f.cache.updateCache(values("stored"));
        f.cache.addChange(new UserDataChangeString("Points","pending"),true);
        java.sql.SQLException offline=new java.sql.SQLException("unavailable");when(f.data.getValuesStrict()).thenThrow(offline);
        IllegalStateException result=assertThrows(IllegalStateException.class,f.cache::cache);assertSame(offline,result.getCause());
        assertEquals("pending",f.value());assertEquals("keep",f.cache.getCache().get("Dynamic").getString());
        assertTrue(f.cache.hasChangesToProcess());verify(f.data,never()).getKeys();verify(f.data,never()).getValues();
    }
    @Test void actualMissingIdentityLoadsDefaultsFromOneCheckedRead() throws Exception {
        Fixture f=new Fixture();when(f.data.getValuesStrict()).thenReturn(new HashMap<>());f.cache.cache();
        assertEquals("",f.value());verify(f.data,times(1)).getValuesStrict();verify(f.data,never()).getKeys();verify(f.data,never()).getValues();
    }
    private static HashMap<String,DataValue> values(String points) {
        HashMap<String,DataValue> data=new HashMap<>();data.put("Points",new DataValueString(points));
        data.put("Dynamic",new DataValueString("keep"));return data;
    }
    private static class BlockingRead {
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        BlockingRead(Fixture f) throws Exception {
            doAnswer(call->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return values("stale");})
                .when(f.data).getValuesStrict();
        }
    }
    private static class Fixture {
        final AdvancedCorePlugin plugin=mock(AdvancedCorePlugin.class);
        final UserDataManager manager=mock(UserDataManager.class);
        final UserManager users=mock(UserManager.class);
        final AdvancedCoreUser user=mock(AdvancedCoreUser.class);
        final UserData data=mock(UserData.class);
        final UserDataCache cache;
        Fixture() {
            when(plugin.getUserStorageOwnership()).thenReturn(new UserStorageOwnership());when(manager.getPlugin()).thenReturn(plugin);when(manager.getTimer()).thenReturn(mock(ScheduledExecutorService.class));
            when(manager.getRegisteredKeysSnapshot()).thenReturn(new ArrayList<>(Collections.singletonList(new UserDataKeyString("Points"))));
            when(plugin.getUserManager()).thenReturn(users);when(user.getUserData()).thenReturn(data);
            when(users.getUser(any(UUID.class),eq(false))).thenReturn(user);
            when(data.getKeys()).thenAnswer(call->new ArrayList<>(Arrays.asList("Points","Dynamic")));
            cache=spy(new UserDataCache(manager,UUID.randomUUID()));doReturn(user).when(cache).getUser();
        }
        String value(){return cache.getCache().get("Points").getString();}
    }
}

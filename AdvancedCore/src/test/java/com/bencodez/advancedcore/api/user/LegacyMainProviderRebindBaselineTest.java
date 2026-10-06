package com.bencodez.advancedcore.api.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.user.userstorage.mysql.MySQL;

class LegacyMainProviderRebindBaselineTest {
    @Test void nativeReplacementMustNotCloseThePoolWhileAnAcceptedCheckedWriteIsRunning() throws Exception {
        LegacyDirectUserDataTest.Fixture f=new LegacyDirectUserDataTest.Fixture();
        MySQL next=mock(MySQL.class);field(f.plugin,"mysql",f.mysql);
        doCallRealMethod().when(f.plugin).setMysql(any());
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        doAnswer(call -> {entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));return null;}).when(f.mysql).updateStrict(anyString(),anyList());
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try {
            Future<?> writing=workers.submit(() -> f.data.setInt("Points",9,false));
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            Future<?> replacing=workers.submit(() -> f.plugin.setMysql(next));
            assertThrows(TimeoutException.class,() -> replacing.get(50,TimeUnit.MILLISECONDS));
            verify(f.mysql,never()).close();
            release.countDown();writing.get(2,TimeUnit.SECONDS);replacing.get(2,TimeUnit.SECONDS);
        } finally {release.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @Test void assigningTheCurrentProviderMustNotDisconnectThatSameProvider() throws Exception {
        LegacyDirectUserDataTest.Fixture f=new LegacyDirectUserDataTest.Fixture();field(f.plugin,"mysql",f.mysql);
        doCallRealMethod().when(f.plugin).setMysql(any());
        f.plugin.setMysql(f.mysql);verify(f.mysql,never()).close();
    }
    @Test void pendingOldCacheBatchIsAcknowledgedBeforeProviderCloseWithoutChangeCallbacks() throws Exception {
        LegacyDirectUserDataTest.Fixture f=new LegacyDirectUserDataTest.Fixture();bind(f);
        f.cache.addChange(new com.bencodez.advancedcore.api.user.usercache.change.UserDataChangeInt("Points",19),true);
        doAnswer(call -> {verify(f.mysql).updateStrict(anyString(),anyList());assertTrue(f.manager.getUserDataCache().isEmpty());return null;}).when(f.mysql).close();
        MySQL next=mock(MySQL.class);f.plugin.setMysql(next);assertSame(next,f.plugin.getMysql());verify(f.users,never()).onChange(any(),any());
        try(com.bencodez.advancedcore.api.user.usercache.UserStorageOwnership.Scope nextWork=f.plugin.getUserStorageOwnership().admit()){}
    }
    @Test void failedOldCacheFlushRetainsOldProviderAndBatchUntilExplicitRetry() throws Exception {
        LegacyDirectUserDataTest.Fixture f=new LegacyDirectUserDataTest.Fixture();bind(f);
        f.cache.addChange(new com.bencodez.advancedcore.api.user.usercache.change.UserDataChangeInt("Points",19),true);
        doThrow(new java.sql.SQLException("fixture offline")).when(f.mysql).updateStrict(anyString(),anyList());
        MySQL next=mock(MySQL.class);assertThrows(IllegalStateException.class,() -> f.plugin.setMysql(next));
        assertSame(f.mysql,f.plugin.getMysql());assertTrue(f.cache.hasChangesToProcess());verify(f.mysql,never()).close();verifyNoInteractions(next);
        assertThrows(IllegalStateException.class,f.plugin.getUserStorageOwnership()::admit);
        doNothing().when(f.mysql).updateStrict(anyString(),anyList());f.plugin.setMysql(next);assertSame(next,f.plugin.getMysql());assertFalse(f.cache.hasChangesToProcess());
    }
    @Test void failedOldCloseRetainsPointerAndSealsNewWorkWithoutPublishingCandidate() throws Exception {
        LegacyDirectUserDataTest.Fixture f=new LegacyDirectUserDataTest.Fixture();bind(f);MySQL next=mock(MySQL.class);
        IllegalStateException failure=new IllegalStateException("close unacknowledged");doThrow(failure).when(f.mysql).close();
        assertSame(failure,assertThrows(IllegalStateException.class,() -> f.plugin.setMysql(next)));assertSame(f.mysql,f.plugin.getMysql());verifyNoInteractions(next);
        assertThrows(IllegalStateException.class,f.plugin.getUserStorageOwnership()::admit);
        doNothing().when(f.mysql).close();f.plugin.setMysql(next);assertSame(next,f.plugin.getMysql());
    }
    @Test void replacementAfterFinalRetirementCannotPublishOrReopenStorage() throws Exception {
        LegacyDirectUserDataTest.Fixture f=new LegacyDirectUserDataTest.Fixture();bind(f);MySQL next=mock(MySQL.class);
        f.plugin.getUserStorageOwnership().retire(0,TimeUnit.NANOSECONDS,() -> {},() -> {});
        assertThrows(IllegalStateException.class,() -> f.plugin.setMysql(next));assertSame(f.mysql,f.plugin.getMysql());verify(f.mysql,never()).close();verifyNoInteractions(next);
    }
    private static void bind(LegacyDirectUserDataTest.Fixture f) throws Exception {
        field(f.plugin,"mysql",f.mysql);field(f.plugin,"userManager",f.users);
        doCallRealMethod().when(f.plugin).getMysql();doCallRealMethod().when(f.plugin).setMysql(any());doCallRealMethod().when(f.manager).clearCacheForShutdown();
    }
    private static void field(AdvancedCorePlugin plugin,String name,Object value) throws Exception {
        java.lang.reflect.Field field=AdvancedCorePlugin.class.getDeclaredField(name);field.setAccessible(true);field.set(plugin,value);
    }
}

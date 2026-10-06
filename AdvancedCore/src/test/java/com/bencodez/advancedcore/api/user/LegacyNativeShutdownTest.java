package com.bencodez.advancedcore.api.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.user.usercache.change.UserDataChangeInt;

class LegacyNativeShutdownTest {
    private LegacyDirectUserDataTest.Fixture fixture() throws Exception {
        LegacyDirectUserDataTest.Fixture f = new LegacyDirectUserDataTest.Fixture();
        set(f.plugin,"userStorageOwnership",f.plugin.getUserStorageOwnership()); set(f.plugin,"userManager",f.users); set(f.plugin,"mysql",f.mysql);
        ScheduledExecutorService shared = f.plugin.getTimer(), cache = f.manager.getTimer();
        set(f.plugin,"timer",shared); when(shared.awaitTermination(anyLong(),any())).thenReturn(true);
        when(cache.awaitTermination(anyLong(),any())).thenReturn(true);
        when(f.plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        doCallRealMethod().when(f.manager).clearCacheForShutdown(); doCallRealMethod().when(f.plugin).onDisable();
        return f;
    }
    @Test void realDisableFlushesBeforeProviderCloseAndSuppressesFinalChangeNotifications() throws Exception {
        LegacyDirectUserDataTest.Fixture f=fixture(); List<String> order=new ArrayList<>(); f.cache.addChange(new UserDataChangeInt("Points",9),true);
        doAnswer(call->{order.add("pre-unload");verify(f.plugin.getTimer(),never()).shutdown();return null;}).when(f.plugin).onPreUnLoad();
        doAnswer(call->{order.add("unload");return null;}).when(f.plugin).onUnLoad();
        doAnswer(call->{order.add("flush");return null;}).when(f.mysql).updateStrict(anyString(),anyList());
        doAnswer(call->{assertTrue(f.manager.getUserDataCache().isEmpty());order.add("close");return null;}).when(f.mysql).close();
        f.plugin.onDisable();assertEquals(Arrays.asList("pre-unload","unload","flush","close"),order);
        verify(f.users,never()).onChange(any(),any());verify(f.plugin.getTimer(),never()).shutdownNow();verify(f.manager.getTimer(),never()).shutdownNow();
        assertThrows(IllegalStateException.class,()->f.data.setInt("Points",11,false));
    }
    @Test void failedFinalFlushRetainsProviderAndPendingCacheAndReportsFailure() throws Exception {
        LegacyDirectUserDataTest.Fixture f=fixture(); f.cache.addChange(new UserDataChangeInt("Points",9),true);
        java.sql.SQLException offline=new java.sql.SQLException("offline");doThrow(offline).when(f.mysql).updateStrict(anyString(),anyList());
        assertSame(offline,assertThrows(IllegalStateException.class,f.plugin::onDisable).getCause());
        verify(f.mysql,never()).close();assertTrue(f.cache.hasChangesToProcess());assertNotNull(f.cache.getUuid());verify(f.users,never()).onChange(any(),any());
        doNothing().when(f.mysql).updateStrict(anyString(),anyList());f.plugin.onDisable();verify(f.mysql).close();assertTrue(f.manager.getUserDataCache().isEmpty());
    }
    @Test void producerTimeoutNeverClosesProviderOrInterruptsItsAcceptedWork() throws Exception {
        LegacyDirectUserDataTest.Fixture f=fixture(); ScheduledExecutorService cache=f.manager.getTimer();when(cache.awaitTermination(anyLong(),any())).thenReturn(false);
        assertThrows(IllegalStateException.class,f.plugin::onDisable);verify(f.mysql,never()).close();verify(f.plugin,never()).onUnLoad();
        verify(cache,never()).shutdownNow();verify(f.plugin.getTimer(),never()).shutdownNow();verify(f.plugin.getTimer(),never()).shutdown();
    }
    @Test void interruptedDrainPreservesInterruptAndProvider() throws Exception {
        LegacyDirectUserDataTest.Fixture f=fixture();when(f.manager.getTimer().awaitTermination(anyLong(),any())).thenThrow(new InterruptedException("interrupted"));
        try {assertThrows(IllegalStateException.class,f.plugin::onDisable);assertTrue(Thread.currentThread().isInterrupted());verify(f.mysql,never()).close();}
        finally {Thread.interrupted();}
    }
    @Test void liveSharedCheckpointExecutorPreventsProviderRetirementWithoutForcedCancellation() throws Exception {
        LegacyDirectUserDataTest.Fixture f=fixture();ScheduledExecutorService shared=f.plugin.getTimer();
        when(shared.awaitTermination(anyLong(),any())).thenReturn(false);
        IllegalStateException failure=assertThrows(IllegalStateException.class,f.plugin::onDisable);
        assertTrue(failure.getMessage().contains("shared storage"));
        verify(shared).shutdown();verify(shared,never()).shutdownNow();
        verify(f.mysql,never()).close();verify(f.plugin,never()).onUnLoad();
    }
    @Test void liveProducerKeepsSharedStorageAvailableUntilItsAcceptedWorkSettles() throws Exception {
        LegacyDirectUserDataTest.Fixture f=fixture();ScheduledExecutorService login=mock(ScheduledExecutorService.class);
        set(f.plugin,"loginTimer",login);when(login.awaitTermination(anyLong(),any())).thenReturn(false);
        IllegalStateException failure=assertThrows(IllegalStateException.class,f.plugin::onDisable);
        assertTrue(failure.getMessage().contains("login"));verify(login).shutdown();verify(login,never()).shutdownNow();
        verify(f.plugin.getTimer(),never()).shutdown();verify(f.mysql,never()).close();
    }

    @Test void unloadCanAddSynchronousPendingDataForTheFinalFlushWithoutSchedulingStoppedTimer() throws Exception {
        LegacyDirectUserDataTest.Fixture f=fixture(); ScheduledExecutorService cache=f.manager.getTimer();clearInvocations(cache);
        doAnswer(call->{f.cache.addChange(new UserDataChangeInt("Points",9),true);return null;}).when(f.plugin).onUnLoad();
        f.plugin.onDisable();verify(cache,never()).schedule(any(Runnable.class),anyLong(),any());verify(f.mysql).updateStrict(anyString(),anyList());verify(f.mysql).close();
    }
    @Test void producerCanSubmitStorageBeforeSharedExecutorIsStopped() throws Exception {
        LegacyDirectUserDataTest.Fixture f=fixture(); ScheduledExecutorService login=mock(ScheduledExecutorService.class),shared=f.plugin.getTimer();set(f.plugin,"loginTimer",login);
        doAnswer(call->{((Runnable)call.getArgument(0)).run();return null;}).when(shared).execute(any());
        when(login.awaitTermination(anyLong(),any())).thenAnswer(call->{verify(shared,never()).shutdown();f.data.setInt("Points",9,false,true);return true;});
        f.plugin.onDisable();verify(f.mysql).updateStrict(anyString(),anyList());verify(f.mysql).close();verify(shared).shutdown();
    }
    @Test void partialStartupDoesNotCreateManagersAndClosesOnlyAnInitializedSqliteConnection() throws Exception {
        AdvancedCorePlugin plugin=mock(AdvancedCorePlugin.class);set(plugin,"userStorageOwnership",new com.bencodez.advancedcore.api.user.usercache.UserStorageOwnership());
        com.bencodez.simpleapi.sql.sqlite.Database database=mock(com.bencodez.simpleapi.sql.sqlite.Database.class);
        com.bencodez.simpleapi.sql.sqlite.db.SQLite sqlite=mock(com.bencodez.simpleapi.sql.sqlite.db.SQLite.class);java.sql.Connection connection=mock(java.sql.Connection.class);
        when(database.getDB()).thenReturn(sqlite);when(sqlite.getConnection()).thenReturn(connection);set(plugin,"database",database);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());doCallRealMethod().when(plugin).onDisable();plugin.onDisable();
        verify(connection).close();verify(sqlite,never()).getSQLConnection();verify(plugin,never()).getUserManager();verify(plugin,never()).getSQLiteUserTable();
    }
    private void set(Object target,String field,Object value)throws Exception {java.lang.reflect.Field f=AdvancedCorePlugin.class.getDeclaredField(field);f.setAccessible(true);f.set(target,value);}
}

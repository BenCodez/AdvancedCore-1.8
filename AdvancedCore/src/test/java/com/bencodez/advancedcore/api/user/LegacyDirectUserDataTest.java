package com.bencodez.advancedcore.api.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.user.usercache.*;
import com.bencodez.advancedcore.api.user.usercache.change.UserDataChangeInt;
import com.bencodez.advancedcore.api.user.userstorage.mysql.MySQL;
import com.bencodez.simpleapi.sql.Column;
import com.bencodez.simpleapi.sql.data.*;

class LegacyDirectUserDataTest {
    @Test void failedDirectWriteDoesNotPublishOrNotify() throws Exception {
        Fixture f=new Fixture();doThrow(new SQLException("offline")).when(f.mysql).updateStrict(anyString(),anyList());
        assertThrows(IllegalStateException.class,()->f.data.setInt("Points",7,false));
        assertEquals(1,f.cache.getCachedValue("Points").getInt());verify(f.users,never()).onChange(any(),any());
    }
    @Test void directWriteFlushesEarlierQueuedValueBeforeCommittingNewValue() throws Exception {
        Fixture f=new Fixture();List<Integer> writes=new ArrayList<>();
        doAnswer(call->{List<Column> columns=call.getArgument(1);writes.add(columns.get(0).getValue().getInt());return null;})
            .when(f.mysql).updateStrict(anyString(),anyList());
        f.cache.addChange(new UserDataChangeInt("Points",2),true);f.data.setInt("Points",7,false);f.cache.processChanges();
        assertEquals(Arrays.asList(2,7),writes);assertEquals(7,f.cache.getCachedValue("Points").getInt());
        assertFalse(f.cache.hasChangesToProcess());
    }
    @Test void directWriteDoesNotNotifyBeforeStorageAcknowledges() throws Exception {
        Fixture f=new Fixture();doAnswer(call->{verify(f.users,never()).onChange(any(),any());
            assertEquals(1,f.cache.getCachedValue("Points").getInt());return null;}).when(f.mysql).updateStrict(anyString(),anyList());
        f.data.setInt("Points",7,false);verify(f.mysql).updateStrict(anyString(),anyList());
        verify(f.users).onChange(eq(f.user),eq("Points"));assertEquals(7,f.cache.getCachedValue("Points").getInt());
    }
    @Test void rejectedAsyncAdmissionLeavesCacheAndNotificationsUnchanged() {
        Fixture f=new Fixture();ScheduledExecutorService timer=f.plugin.getTimer();
        doThrow(new RejectedExecutionException("stopped")).when(timer).execute(any());
        assertThrows(RejectedExecutionException.class,()->f.data.setInt("Points",7,false,true));
        assertEquals(1,f.cache.getCachedValue("Points").getInt());verify(f.users,never()).onChange(any(),any());
    }
    @Test void laterQueuedMutationIsNotOverwrittenByDirectCommit() throws Exception {
        Fixture f=new Fixture();List<Integer> writes=new ArrayList<>();
        doAnswer(call->{List<Column> columns=call.getArgument(1);int value=columns.get(0).getValue().getInt();writes.add(value);
            if(value==7) f.cache.addChange(new UserDataChangeInt("Points",9),true);return null;})
            .when(f.mysql).updateStrict(anyString(),anyList());
        f.data.setInt("Points",7,false);assertEquals(9,f.cache.getCachedValue("Points").getInt());
        f.cache.processChanges();assertEquals(Arrays.asList(7,9),writes);
    }
    @Test void stringFailureAndUncachedFailureRemainVisible() throws Exception {
        Fixture f=new Fixture();doThrow(new SQLException("offline")).when(f.mysql).updateStrict(anyString(),anyList());
        assertThrows(IllegalStateException.class,()->f.data.setString("Points","new",false));
        assertEquals(1,f.cache.getCachedValue("Points").getInt());when(f.user.isCached()).thenReturn(false);
        assertThrows(IllegalStateException.class,()->f.data.setInt("Points",7,false));
        verify(f.users,never()).onChange(any(),any());
    }
    @Test void bothCommittedNotificationsRunEvenWhenFirstCallbackFails() throws Exception {
        Fixture f=new Fixture();f.cache.addChange(new UserDataChangeInt("Points",2),true);
        doThrow(new IllegalStateException("listener failed")).doNothing().when(f.users).onChange(eq(f.user),any(String[].class));
        assertThrows(IllegalStateException.class,()->f.data.setInt("Points",7,false));
        verify(f.mysql,times(2)).updateStrict(anyString(),anyList());verify(f.users,times(2)).onChange(eq(f.user),any(String[].class));
        assertEquals(7,f.cache.getCachedValue("Points").getInt());assertFalse(f.cache.hasChangesToProcess());
    }
    @Test void retirementAndRecursiveDirectWritesAreRejectedDuringStorageCommit() throws Exception {
        Fixture f=new Fixture();doAnswer(call->{assertThrows(IllegalStateException.class,f.cache::dump);
            assertThrows(IllegalStateException.class,()->f.data.setInt("Points",8,false));return null;})
            .when(f.mysql).updateStrict(anyString(),anyList());
        f.data.setInt("Points",7,false);assertEquals(7,f.cache.getCachedValue("Points").getInt());
        verify(f.mysql,times(1)).updateStrict(anyString(),anyList());
    }
    @Test void asyncDirectWritePublishesOnlyAfterScheduledWorkCommits() throws Exception {
        Fixture f=new Fixture();List<Runnable> scheduled=new ArrayList<>();ScheduledExecutorService timer=f.plugin.getTimer();
        doAnswer(call->{scheduled.add(call.getArgument(0));return null;}).when(timer).execute(any());
        f.data.setInt("Points",7,false,true);
        assertEquals(1,f.cache.getCachedValue("Points").getInt());verify(f.mysql,never()).updateStrict(anyString(),anyList());
        verify(f.users,never()).onChange(any(),any());scheduled.get(0).run();
        assertEquals(7,f.cache.getCachedValue("Points").getInt());verify(f.mysql).updateStrict(anyString(),anyList());
    }
    @Test void explicitStorageSetterUsesRequestedBackend() throws Exception {
        Fixture f=new Fixture();com.bencodez.advancedcore.api.user.userstorage.sql.UserTable sqlite=
            mock(com.bencodez.advancedcore.api.user.userstorage.sql.UserTable.class);
        when(f.plugin.getSQLiteUserTable()).thenReturn(sqlite);
        f.data.setString(UserStorage.SQLITE,"Points","7",false);
        verify(sqlite).updateStrict(any(),anyList());verify(f.mysql,never()).updateStrict(anyString(),anyList());
        assertEquals("7",f.cache.getCachedValue("Points").getString());
    }
    @Test void asyncDirectWriteResolvesACachePublishedAfterAdmission() throws Exception {
        Fixture f = new Fixture(); f.manager.getUserDataCache().clear(); when(f.user.isCached()).thenReturn(false);
        List<Runnable> accepted = new ArrayList<>();
        ScheduledExecutorService timer = f.plugin.getTimer();
        doAnswer(call -> { accepted.add(call.getArgument(0)); return null; }).when(timer).execute(any());
        f.data.setInt("Points", 7, false, true);
        UserDataCache replacement = spy(new UserDataCache(f.manager, f.cache.getUuid()));
        doReturn(f.user).when(replacement).getUser();
        HashMap<String, DataValue> values = new HashMap<>(); values.put("Points", new DataValueInt(4)); replacement.updateCache(values);
        f.manager.getUserDataCache().put(replacement.getUuid(), replacement);
        assertEquals(4, replacement.getCachedValue("Points").getInt()); accepted.get(0).run();
        assertEquals(7, replacement.getCachedValue("Points").getInt());
        assertEquals(1, f.cache.getCachedValue("Points").getInt());
        verify(f.mysql).updateStrict(anyString(), anyList());
    }

    static class Fixture {
        final AdvancedCorePlugin plugin=mock(AdvancedCorePlugin.class);
        final UserManager users=mock(UserManager.class);
        final AdvancedCoreUser user=mock(AdvancedCoreUser.class);
        final UserDataManager manager=mock(UserDataManager.class);
        final MySQL mysql=mock(MySQL.class);
        final UserData data=new UserData(user);
        final UserDataCache cache;
        Fixture(){when(plugin.getUserStorageOwnership()).thenReturn(new UserStorageOwnership());
            when(manager.getPlugin()).thenReturn(plugin);cache=spy(new UserDataCache(manager,UUID.randomUUID()));
            java.util.concurrent.ConcurrentHashMap<UUID,UserDataCache> registry=new java.util.concurrent.ConcurrentHashMap<>();registry.put(cache.getUuid(),cache);
            when(manager.getUserDataCache()).thenReturn(registry);when(users.getDataManager()).thenReturn(manager);
            doCallRealMethod().when(manager).writeDirect(any(),anyString(),any(),any());
            String identity=cache.getUuid().toString();when(user.getPlugin()).thenReturn(plugin);when(user.getUUID()).thenReturn(identity);
            when(user.getUserData()).thenReturn(data);when(plugin.getStorageType()).thenReturn(UserStorage.MYSQL);
            when(plugin.getMysql()).thenReturn(mysql);when(plugin.getUserManager()).thenReturn(users);
            when(plugin.getTimer()).thenReturn(mock(ScheduledExecutorService.class));
            when(manager.getPlugin()).thenReturn(plugin);when(manager.getTimer()).thenReturn(mock(ScheduledExecutorService.class));
            when(user.isCached()).thenReturn(true);when(user.getCache()).thenReturn(cache);doReturn(user).when(cache).getUser();
            HashMap<String,DataValue> values=new HashMap<>();values.put("Points",new DataValueInt(1));cache.updateCache(values);}
    }
}

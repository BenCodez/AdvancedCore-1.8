package com.bencodez.advancedcore.api.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.AdvancedCoreConfigOptions;
import com.bencodez.advancedcore.api.user.usercache.change.UserDataChangeInt;

class LegacyConfigurationStorageReloadTest {
    @Test void pendingDataMustFlushUnderOldStorageBeforeOptionsCanChange() throws Exception {
        LegacyDirectUserDataTest.Fixture f=new LegacyDirectUserDataTest.Fixture();
        AdvancedCoreConfigOptions options=mock(AdvancedCoreConfigOptions.class);
        when(f.plugin.getOptions()).thenReturn(options);when(options.getStorageType()).thenReturn(UserStorage.SQLITE);
        field(f.plugin,"userManager",f.users);field(f.plugin,"loadUserData",true);
        doCallRealMethod().when(f.manager).clearCacheForShutdown();
        f.cache.addChange(new UserDataChangeInt("Points",19),true);
        doAnswer(call -> {verify(f.mysql).updateStrict(anyString(),anyList());assertTrue(f.manager.getUserDataCache().isEmpty());return null;}).when(options).load(f.plugin);
        load(f.plugin,true);verify(f.plugin).loadUserAPI(UserStorage.SQLITE);
    }
    @Test void mysqlSetterComposesInsideTheSameSealedConfigurationPublication() throws Exception {
        LegacyDirectUserDataTest.Fixture f=new LegacyDirectUserDataTest.Fixture();AdvancedCoreConfigOptions options=configure(f);
        com.bencodez.advancedcore.api.user.userstorage.mysql.MySQL next=mock(com.bencodez.advancedcore.api.user.userstorage.mysql.MySQL.class);
        field(f.plugin,"mysql",f.mysql);doCallRealMethod().when(f.plugin).getMysql();doCallRealMethod().when(f.plugin).setMysql(any());
        f.cache.addChange(new UserDataChangeInt("Points",19),true);
        doAnswer(call -> {
            assertTrue(f.plugin.getUserStorageOwnership().isReplacingOnCurrentThread());
            assertThrows(IllegalStateException.class,f.plugin.getUserStorageOwnership()::admit);
            verify(f.mysql).updateStrict(anyString(),anyList());f.plugin.setMysql(next);return null;
        }).when(f.plugin).loadUserAPI(UserStorage.MYSQL);
        load(f.plugin,true);assertSame(next,f.plugin.getMysql());verify(f.mysql).close();verify(f.mysql,times(1)).updateStrict(anyString(),anyList());
        assertFalse(f.plugin.getUserStorageOwnership().isReplacingOnCurrentThread());try(com.bencodez.advancedcore.api.user.usercache.UserStorageOwnership.Scope root=f.plugin.getUserStorageOwnership().admit()){}
    }
    @Test void oldFlushFailureCannotMutateOptionsOrStartProviderPreparation() throws Exception {
        LegacyDirectUserDataTest.Fixture f=new LegacyDirectUserDataTest.Fixture();AdvancedCoreConfigOptions options=configure(f);
        f.cache.addChange(new UserDataChangeInt("Points",19),true);doThrow(new java.sql.SQLException("offline")).when(f.mysql).updateStrict(anyString(),anyList());
        assertThrows(IllegalStateException.class,() -> load(f.plugin,true));verify(options,never()).load(any());verify(f.plugin,never()).loadUserAPI(any());verify(f.mysql,never()).close();assertTrue(f.cache.hasChangesToProcess());
    }
    @Test void preparationFailureLeavesAdmissionSealedAndClearsPublicationPrivilegeForRetry() throws Exception {
        LegacyDirectUserDataTest.Fixture f=new LegacyDirectUserDataTest.Fixture();configure(f);
        IllegalStateException failure=new IllegalStateException("candidate failed");doThrow(failure).when(f.plugin).loadUserAPI(UserStorage.MYSQL);
        assertSame(failure,assertThrows(IllegalStateException.class,() -> load(f.plugin,true)));assertFalse(f.plugin.getUserStorageOwnership().isReplacingOnCurrentThread());
        assertThrows(IllegalStateException.class,f.plugin.getUserStorageOwnership()::admit);verify(f.mysql,never()).close();
        doNothing().when(f.plugin).loadUserAPI(UserStorage.MYSQL);load(f.plugin,true);try(com.bencodez.advancedcore.api.user.usercache.UserStorageOwnership.Scope next=f.plugin.getUserStorageOwnership().admit()){}
    }
    @Test void storageReloadCannotUndoFinalShutdownOrMutateSettingsAfterIt() throws Exception {
        LegacyDirectUserDataTest.Fixture f=new LegacyDirectUserDataTest.Fixture();AdvancedCoreConfigOptions options=configure(f);
        f.plugin.getUserStorageOwnership().retire(0,java.util.concurrent.TimeUnit.NANOSECONDS,() -> {},() -> {});
        assertThrows(IllegalStateException.class,() -> load(f.plugin,true));verify(options,never()).load(any());verify(f.plugin,never()).loadUserAPI(any());
    }
    @Test void nonStorageAndDisabledUserDataKeepTheirExistingOptionsOnlyPath() throws Exception {
        for(boolean storage:new boolean[]{false,true}) {
            LegacyDirectUserDataTest.Fixture f=new LegacyDirectUserDataTest.Fixture();AdvancedCoreConfigOptions options=configure(f);
            if(storage)field(f.plugin,"loadUserData",false);
            load(f.plugin,storage);verify(options).load(f.plugin);verify(f.plugin,never()).loadUserAPI(any());
            try(com.bencodez.advancedcore.api.user.usercache.UserStorageOwnership.Scope same=f.plugin.getUserStorageOwnership().admit()){}
        }
    }
    static AdvancedCoreConfigOptions configure(LegacyDirectUserDataTest.Fixture f) throws Exception {
        AdvancedCoreConfigOptions options=mock(AdvancedCoreConfigOptions.class);when(f.plugin.getOptions()).thenReturn(options);when(options.getStorageType()).thenReturn(UserStorage.MYSQL);
        field(f.plugin,"userManager",f.users);field(f.plugin,"loadUserData",true);doCallRealMethod().when(f.manager).clearCacheForShutdown();return options;
    }
    static void load(AdvancedCorePlugin plugin,boolean storage) throws Exception {
        java.lang.reflect.Method method=AdvancedCorePlugin.class.getDeclaredMethod("loadConfig",boolean.class);method.setAccessible(true);
        try {method.invoke(plugin,storage);}catch(java.lang.reflect.InvocationTargetException failure){
            Throwable cause=failure.getCause();if(cause instanceof Error)throw (Error)cause;if(cause instanceof RuntimeException)throw (RuntimeException)cause;throw failure;
        }
    }
    static void field(AdvancedCorePlugin plugin,String name,Object value) throws Exception {
        java.lang.reflect.Field field=AdvancedCorePlugin.class.getDeclaredField(name);field.setAccessible(true);field.set(plugin,value);
    }
}

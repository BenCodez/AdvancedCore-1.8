package com.bencodez.advancedcore;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.HashMap;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.api.user.*;
import com.bencodez.advancedcore.api.user.usercache.UserStorageOwnership;
import com.bencodez.advancedcore.api.user.userstorage.mysql.MySQL;

class LegacyStorageConversionTest {
    @Test void conversionMustNotRecreateItsExistingMysqlSource() throws Exception {
        AdvancedCorePlugin plugin=fixture();
        plugin.convertDataStorage(UserStorage.MYSQL, UserStorage.SQLITE);
        verify(plugin,never()).loadUserAPI(UserStorage.MYSQL);
        verify(plugin).loadUserAPI(UserStorage.SQLITE);
    }
    @Test void conversionKeepsForeignWritersOutThroughSourceEnumeration() throws Exception {
        AdvancedCorePlugin plugin=fixture();UserStorageOwnership owner=plugin.getUserStorageOwnership();
        ExecutorService foreign=Executors.newSingleThreadExecutor();
        when(plugin.getUserManager().getAllKeysStrict(UserStorage.MYSQL)).thenAnswer(call -> {
            assertTrue(foreign.submit(() -> {
                try(UserStorageOwnership.Scope ignored=owner.admit()) {return false;}
                catch(IllegalStateException sealed) {return true;}
            }).get(2,TimeUnit.SECONDS), "Conversion source was read with unrelated storage admission open");
            return new HashMap<>();
        });
        try {plugin.convertDataStorage(UserStorage.MYSQL,UserStorage.SQLITE);}
        finally {foreign.shutdownNow();assertTrue(foreign.awaitTermination(2,TimeUnit.SECONDS));}
        try(UserStorageOwnership.Scope ignored=owner.admit()) {}
    }
    @Test void copyWritesRemainInsideMaintenanceAndUseExplicitDestination() throws Exception {
        AdvancedCorePlugin plugin=fixture();java.util.UUID id=java.util.UUID.randomUUID();
        AdvancedCoreUser user=mock(AdvancedCoreUser.class);UserData data=mock(UserData.class);
        java.util.ArrayList<com.bencodez.simpleapi.sql.Column> columns=new java.util.ArrayList<>();
        HashMap<java.util.UUID,java.util.ArrayList<com.bencodez.simpleapi.sql.Column>> source=new HashMap<>();source.put(id,columns);
        when(plugin.getUserManager().getAllKeysStrict(UserStorage.MYSQL)).thenReturn(source);
        when(plugin.getUserManager().getUser(id,false)).thenReturn(user);when(user.getData()).thenReturn(data);
        HashMap<String,com.bencodez.simpleapi.sql.data.DataValue> values=new HashMap<>();when(data.convert(columns)).thenReturn(values);
        ExecutorService foreign=Executors.newSingleThreadExecutor();
        doAnswer(call -> {
            try(UserStorageOwnership.Scope nested=plugin.getUserStorageOwnership().admit()) {}
            assertTrue(foreign.submit(() -> {assertThrows(IllegalStateException.class,plugin.getUserStorageOwnership()::admit);return true;}).get(2,TimeUnit.SECONDS));return null;
        }).when(data).setValues(UserStorage.SQLITE,values);
        try {plugin.convertDataStorage(UserStorage.MYSQL,UserStorage.SQLITE);verify(data).setValues(UserStorage.SQLITE,values);verify(user).dontCache();}
        finally {foreign.shutdownNow();assertTrue(foreign.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @Test void failedCopyRetainsSealAndOriginalFailure() throws Exception {
        AdvancedCorePlugin plugin=fixture();IllegalStateException failure=new IllegalStateException("source failed");
        when(plugin.getUserManager().getAllKeysStrict(UserStorage.MYSQL)).thenThrow(failure);
        assertSame(failure,assertThrows(IllegalStateException.class,() -> plugin.convertDataStorage(UserStorage.MYSQL,UserStorage.SQLITE)));
        assertThrows(IllegalStateException.class,plugin.getUserStorageOwnership()::admit);
        verify(plugin,never()).loadUserAPI(UserStorage.MYSQL);
    }
    @Test void nullTypesFailBeforeProviderSideEffects() throws Exception {
        AdvancedCorePlugin plugin=fixture();assertThrows(RuntimeException.class,() -> plugin.convertDataStorage(null,UserStorage.SQLITE));
        assertThrows(RuntimeException.class,() -> plugin.convertDataStorage(UserStorage.MYSQL,null));verify(plugin,never()).loadUserAPI(any());
        try(UserStorageOwnership.Scope normal=plugin.getUserStorageOwnership().admit()) {}
    }
    @Test void checkedSourceFailurePrecedesDestinationCreationAndKeepsItsCause() throws Exception {
        AdvancedCorePlugin plugin=fixture();java.sql.SQLException failure=new java.sql.SQLException("incomplete source");
        when(plugin.getUserManager().getAllKeysStrict(UserStorage.MYSQL)).thenThrow(failure);
        assertSame(failure,assertThrows(IllegalStateException.class,() -> plugin.convertDataStorage(UserStorage.MYSQL,UserStorage.SQLITE)).getCause());
        verify(plugin,never()).loadUserAPI(UserStorage.SQLITE);verify(plugin.getUserManager(),never()).getAllKeys(any());
        assertThrows(IllegalStateException.class,plugin.getUserStorageOwnership()::admit);
    }
    AdvancedCorePlugin fixture() throws Exception {
        AdvancedCorePlugin plugin=mock(AdvancedCorePlugin.class);
        when(plugin.getUserStorageOwnership()).thenReturn(new UserStorageOwnership());
        UserManager users=mock(UserManager.class);when(plugin.getUserManager()).thenReturn(users);
        when(users.getAllKeysStrict(UserStorage.MYSQL)).thenReturn(new HashMap<>());
        when(plugin.getMysql()).thenReturn(mock(MySQL.class));
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        doCallRealMethod().when(plugin).convertDataStorage(any(),any());
        return plugin;
    }
}

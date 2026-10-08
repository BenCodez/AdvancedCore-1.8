package com.bencodez.advancedcore;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.sql.Connection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.bencodez.advancedcore.api.user.*;
import com.bencodez.advancedcore.api.user.usercache.*;
import com.bencodez.simpleapi.sql.sqlite.Database;

class LegacySQLiteProviderLifecycleTest {
    @TempDir Path folder;
    @Test void repeatedNativeSQLiteInitializationClosesItsPredecessorConnection() throws Exception {
        AdvancedCorePlugin plugin=fixture();Connection old=null;
        try (org.mockito.MockedStatic<AdvancedCorePlugin> global=mockStatic(AdvancedCorePlugin.class)) {
            global.when(AdvancedCorePlugin::getInstance).thenReturn(plugin);
            plugin.loadUserAPI(UserStorage.SQLITE);old=database(plugin).getDB().getConnection();assertNotNull(old);assertFalse(old.isClosed());
            plugin.loadUserAPI(UserStorage.SQLITE);assertTrue(old.isClosed(),"Native reload abandoned its old SQLite connection");
            assertFalse(database(plugin).getDB().getConnection().isClosed());
        }finally{if(old!=null)old.close();cleanup(plugin);}
    }
    @Test void nativeInitializationCannotCreateANewProviderAfterFinalRetirement() throws Exception {
        AdvancedCorePlugin plugin=fixture();plugin.getUserStorageOwnership().retire(0,java.util.concurrent.TimeUnit.NANOSECONDS,() -> {},() -> {});
        try (org.mockito.MockedStatic<AdvancedCorePlugin> global=mockStatic(AdvancedCorePlugin.class)) {
            global.when(AdvancedCorePlugin::getInstance).thenReturn(plugin);
            assertThrows(IllegalStateException.class,() -> plugin.loadUserAPI(UserStorage.SQLITE));assertNull(database(plugin));}
        finally{cleanup(plugin);}
    }
    @Test void lazyInitializationCanRunInsideAnAcceptedReadAndPreservesPublicOverrideDispatch() throws Exception {
        AdvancedCorePlugin plugin=fixture();field(plugin,"loadUserData",true);doCallRealMethod().when(plugin).getSQLiteUserTable();when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        java.util.concurrent.atomic.AtomicInteger dispatches=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {dispatches.incrementAndGet();return call.callRealMethod();}).when(plugin).loadUserAPI(UserStorage.SQLITE);
        try (org.mockito.MockedStatic<AdvancedCorePlugin> global=mockStatic(AdvancedCorePlugin.class)) {
            global.when(AdvancedCorePlugin::getInstance).thenReturn(plugin);
            try(UserStorageOwnership.Scope read=plugin.getUserStorageOwnership().admit()) {assertNotNull(plugin.getSQLiteUserTable());assertNotNull(plugin.getSQLiteUserTable());}
            assertEquals(1,dispatches.get());assertFalse(database(plugin).getDB().getConnection().isClosed());
        }finally{cleanup(plugin);}
    }
    @Test void finalRetirementAlsoForbidsLazyBootstrapBeforeFactorySideEffects() throws Exception {
        AdvancedCorePlugin plugin=fixture();field(plugin,"loadUserData",true);doCallRealMethod().when(plugin).getSQLiteUserTable();
        plugin.getUserStorageOwnership().retire(0,java.util.concurrent.TimeUnit.NANOSECONDS,() -> {},() -> {});
        assertThrows(IllegalStateException.class,plugin::getSQLiteUserTable);verify(plugin,never()).createSQLiteProvider();assertNull(database(plugin));
    }
    @Test void failedOldCloseRetainsPredecessorAndClosesUnpublishedOwnedCandidate() throws Exception {
        AdvancedCorePlugin plugin=fixture();Database old=provider(),candidate=provider();field(plugin,"database",old);
        doReturn(candidate).when(plugin).createSQLiteProvider();java.sql.SQLException failure=new java.sql.SQLException("close failed");Connection oldConnection=old.getDB().getConnection();doThrow(failure).when(oldConnection).close();
        IllegalStateException observed=assertThrows(IllegalStateException.class,() -> plugin.loadUserAPI(UserStorage.SQLITE));assertSame(failure,observed.getCause());
        assertSame(old,database(plugin));verify(candidate.getDB().getConnection()).close();assertThrows(IllegalStateException.class,plugin.getUserStorageOwnership()::admit);
        doNothing().when(oldConnection).close();Database retry=provider();doReturn(retry).when(plugin).createSQLiteProvider();plugin.loadUserAPI(UserStorage.SQLITE);assertSame(retry,database(plugin));cleanup(plugin);
    }
    @Test void failedPreparationCannotClosePredecessorOrPublishAnything() throws Exception {
        AdvancedCorePlugin plugin=fixture();Database old=provider();field(plugin,"database",old);IllegalStateException failure=new IllegalStateException("preparation failed");doThrow(failure).when(plugin).createSQLiteProvider();
        assertSame(failure,assertThrows(IllegalStateException.class,() -> plugin.loadUserAPI(UserStorage.SQLITE)));assertSame(old,database(plugin));verify(old.getDB().getConnection(),never()).close();assertThrows(IllegalStateException.class,plugin.getUserStorageOwnership()::admit);cleanup(plugin);
    }
    @Test void publicInitializationRejectsSelfRetirementAndNullTypeBeforeTouchingProvider() throws Exception {
        AdvancedCorePlugin plugin=fixture();
        try(UserStorageOwnership.Scope read=plugin.getUserStorageOwnership().admit()) {assertThrows(IllegalStateException.class,() -> plugin.loadUserAPI(UserStorage.SQLITE));}
        assertThrows(NullPointerException.class,() -> plugin.loadUserAPI(null));verify(plugin,never()).createSQLiteProvider();assertNull(database(plugin));
    }
    @Test void addingSQLiteDoesNotCloseTheMysqlSourceRequiredByExplicitConversions() throws Exception {
        AdvancedCorePlugin plugin=fixture();com.bencodez.advancedcore.api.user.userstorage.mysql.MySQL mysql=mock(com.bencodez.advancedcore.api.user.userstorage.mysql.MySQL.class);field(plugin,"mysql",mysql);Database next=provider();doReturn(next).when(plugin).createSQLiteProvider();
        plugin.loadUserAPI(UserStorage.SQLITE);verify(mysql,never()).close();assertSame(next,database(plugin));cleanup(plugin);
    }
    @Test void factoryOwnedMysqlCandidateIsCleanedOnFailedPredecessorClose() throws Exception {
        AdvancedCorePlugin plugin=fixture();com.bencodez.advancedcore.api.user.userstorage.mysql.MySQL old=mock(com.bencodez.advancedcore.api.user.userstorage.mysql.MySQL.class),candidate=mock(com.bencodez.advancedcore.api.user.userstorage.mysql.MySQL.class);field(plugin,"mysql",old);
        doCallRealMethod().when(plugin).setMysql(any());doReturn(candidate).when(plugin).createMySQLProvider();IllegalStateException failure=new IllegalStateException("old close failed");doThrow(failure).when(old).close();
        assertSame(failure,assertThrows(IllegalStateException.class,() -> plugin.loadUserAPI(UserStorage.MYSQL)));verify(candidate).close();
    }
    @Test void concurrentLazyReadersPublishOnlyOneProviderWithoutWaitingForTheirOwnAdmission() throws Exception {
        AdvancedCorePlugin plugin=fixture();field(plugin,"loadUserData",true);doCallRealMethod().when(plugin).getSQLiteUserTable();when(plugin.getStorageType()).thenReturn(UserStorage.SQLITE);
        Database next=provider();com.bencodez.advancedcore.api.user.userstorage.sql.UserTable table=mock(com.bencodez.advancedcore.api.user.userstorage.sql.UserTable.class);when(next.getTables()).thenReturn(java.util.Collections.singletonList(table));
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);java.util.concurrent.ExecutorService workers=java.util.concurrent.Executors.newFixedThreadPool(2);
        doAnswer(call -> {entered.countDown();assertTrue(release.await(3,java.util.concurrent.TimeUnit.SECONDS));return next;}).when(plugin).createSQLiteProvider();
        try {
            java.util.concurrent.Future<com.bencodez.advancedcore.api.user.userstorage.sql.UserTable> first=workers.submit(plugin::getSQLiteUserTable);assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.Future<com.bencodez.advancedcore.api.user.userstorage.sql.UserTable> second=workers.submit(plugin::getSQLiteUserTable);release.countDown();
            assertSame(table,first.get(2,java.util.concurrent.TimeUnit.SECONDS));assertSame(table,second.get(2,java.util.concurrent.TimeUnit.SECONDS));verify(plugin,times(1)).createSQLiteProvider();
        }finally{release.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(2,java.util.concurrent.TimeUnit.SECONDS));cleanup(plugin);}
    }
    static Database provider() throws Exception {
        Database db=mock(Database.class);com.bencodez.simpleapi.sql.sqlite.db.SQLite sqlite=mock(com.bencodez.simpleapi.sql.sqlite.db.SQLite.class);Connection connection=mock(Connection.class);when(db.getDB()).thenReturn(sqlite);when(sqlite.getConnection()).thenReturn(connection);return db;
    }
    AdvancedCorePlugin fixture() throws Exception {
        AdvancedCorePlugin plugin=mock(AdvancedCorePlugin.class);AdvancedCoreConfigOptions options=mock(AdvancedCoreConfigOptions.class);
        UserManager users=mock(UserManager.class);UserDataManager data=mock(UserDataManager.class);
        when(plugin.getOptions()).thenReturn(options);when(options.getStorageType()).thenReturn(UserStorage.SQLITE);
        when(plugin.getUserStorageOwnership()).thenReturn(new UserStorageOwnership());when(plugin.getUserManager()).thenReturn(users);when(users.getDataManager()).thenReturn(data);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());when(plugin.getName()).thenReturn("SQLiteFixture");
        field(plugin,"sqliteInitialization",new Object());field(plugin,"sqliteBootstrap",ThreadLocal.withInitial(() -> false));
        doCallRealMethod().when(plugin).loadUserAPI(any());doCallRealMethod().when(plugin).createSQLiteProvider();return plugin;
    }
    static void field(AdvancedCorePlugin plugin,String name,Object value)throws Exception {java.lang.reflect.Field f=AdvancedCorePlugin.class.getDeclaredField(name);f.setAccessible(true);f.set(plugin,value);}
    static Database database(AdvancedCorePlugin plugin)throws Exception {java.lang.reflect.Field f=AdvancedCorePlugin.class.getDeclaredField("database");f.setAccessible(true);return (Database)f.get(plugin);}
    static void cleanup(AdvancedCorePlugin plugin)throws Exception {Database db=database(plugin);if(db!=null&&db.getDB().getConnection()!=null)db.getDB().getConnection().close();}
}

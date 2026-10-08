package com.bencodez.advancedcore.tests.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.user.UserManager;
import com.bencodez.advancedcore.api.user.usercache.UserDataManager;
import com.bencodez.advancedcore.api.user.usercache.keys.*;
import com.bencodez.advancedcore.api.user.userstorage.sql.UserTable;
import com.bencodez.simpleapi.sql.Column;
import com.bencodez.simpleapi.sql.DataType;
import com.bencodez.simpleapi.sql.sqlite.db.SQLite;

class LegacySchemaRegistrationTest {
    @Test void registrationSharesSchemaSnapshotMonitor() throws Exception {
        Fixture f=new Fixture();AtomicReference<Throwable> failed=new AtomicReference<>();
        Thread registration=new Thread(()->{try {f.manager.addKey(new UserDataKeyInt("late"));}catch(Throwable t){failed.set(t);}},"schema-registration");
        try {
            synchronized(f.manager) {
                registration.start();long bound=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(registration.getState()!=Thread.State.BLOCKED && registration.isAlive() && System.nanoTime()<bound)Thread.yield();
                assertEquals(Thread.State.BLOCKED,registration.getState(),"Registration uses schema snapshot monitor");
                assertFalse(f.manager.isInt("late"));
            }
        } finally {registration.join(2000);}
        assertFalse(registration.isAlive());assertNull(failed.get());assertTrue(f.manager.isInt("late"));
    }
    @Test void sqliteMigrationUsesDetachedRegistrationSnapshotAndNextPassIncludesLateKey() throws Exception {
        Fixture f=new Fixture();Class.forName("org.sqlite.JDBC");
        try(Connection connection=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try(Statement statement=connection.createStatement()) {statement.executeUpdate("CREATE TABLE users (uuid VARCHAR(100) PRIMARY KEY)");statement.executeUpdate("INSERT INTO users(uuid) VALUES ('existing-user')");}
            SQLite sqlite=mock(SQLite.class);when(sqlite.getSQLConnection()).thenReturn(connection);
            UserTable table=spy(new UserTable(f.plugin,"users",new ArrayList<>(Arrays.asList(new Column("uuid",DataType.STRING)))));table.setSqLite(sqlite);
            AtomicBoolean registered=new AtomicBoolean();
            doAnswer(c->{assertFalse(Thread.holdsLock(f.manager),"DDL runs outside key registration monitor");if(registered.compareAndSet(false,true))f.manager.addKey(new UserDataKeyInt("late"));return c.callRealMethod();}).when(table).addColoumn(any(UserDataKey.class));
            table.addCustomColumns();assertFalse(table.getTableColumns().contains("late"));
            table.addCustomColumns();table.addCustomColumns();assertEquals(1,Collections.frequency(table.getTableColumns(),"late"));
            try(Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery("SELECT uuid,late FROM users")) {assertTrue(rows.next());assertEquals("existing-user",rows.getString(1));assertEquals(0,rows.getInt(2));assertFalse(rows.next());}
        }
    }
    @Test void schemaSnapshotPreservesLegacyMutableCollectionAndKeyApi() throws Exception {
        Fixture f=new Fixture();UserDataKey key=new UserDataKeyString("custom");f.manager.addKey(key);
        ArrayList<UserDataKey> legacy=f.manager.getKeys();ArrayList<UserDataKey> snapshot=f.manager.getRegisteredKeysSnapshot();
        assertSame(legacy,f.manager.getKeys());assertNotSame(legacy,snapshot);assertTrue(snapshot.contains(key));
        f.manager.addKey(new UserDataKeyBoolean("new-flag"));assertEquals(snapshot.size()+1,legacy.size());
        snapshot.clear();assertFalse(legacy.isEmpty());assertTrue(f.manager.isBoolean("new-flag"));
        key.setColumnType("VARCHAR(42)");assertSame(key,f.manager.getRegisteredKeysSnapshot().get(legacy.indexOf(key)));
        assertEquals("VARCHAR(42)",key.getColumnType());
    }
    @Test void sqliteProjectionUsesOneMembershipSnapshotWithoutHoldingRegistrationLock() throws Exception {
        Fixture f=new Fixture();UserDataKey key=mock(UserDataKey.class);when(key.getKey()).thenReturn("custom");
        AtomicBoolean registered=new AtomicBoolean();
        when(key.getColumnType()).thenAnswer(c->{assertFalse(Thread.holdsLock(f.manager));if(registered.compareAndSet(false,true))f.manager.addKey(new UserDataKeyInt("late_projection"));return "TEXT";});
        f.manager.addKey(key);UserTable table=new UserTable(f.plugin,"users",new ArrayList<>(Arrays.asList(new Column("uuid",DataType.STRING))));
        try(org.mockito.MockedStatic<AdvancedCorePlugin> singleton=mockStatic(AdvancedCorePlugin.class)) {
            singleton.when(AdvancedCorePlugin::getInstance).thenReturn(f.plugin);
            String first=table.getQuery();assertTrue(first.contains("custom TEXT"));assertFalse(first.contains("late_projection"));
            assertTrue(table.getQuery().contains("late_projection"));
        }
    }
    @Test void cacheDefaultPopulationUsesDetachedMembershipAndRunsDefaultsOutsideRegistrationLock() throws Exception {
        Fixture f=new Fixture();UUID id=UUID.randomUUID();
        com.bencodez.advancedcore.api.user.AdvancedCoreUser user=mock(com.bencodez.advancedcore.api.user.AdvancedCoreUser.class);
        com.bencodez.advancedcore.api.user.UserData data=mock(com.bencodez.advancedcore.api.user.UserData.class);
        when(f.plugin.getUserStorageOwnership()).thenReturn(new com.bencodez.advancedcore.api.user.usercache.UserStorageOwnership());
        when(f.plugin.getUserManager().getUser(id,false)).thenReturn(user);when(user.getUserData()).thenReturn(data);
        when(data.getValuesStrict()).thenAnswer(c->new HashMap<>());
        AtomicBoolean registered=new AtomicBoolean();
        f.manager.addKey(new UserDataKeyString("register-on-default") {
            @Override public com.bencodez.simpleapi.sql.data.DataValue getDefault() {
                assertFalse(Thread.holdsLock(f.manager),"Extension defaults run outside registration monitor");
                if(registered.compareAndSet(false,true))f.manager.addKey(new UserDataKeyInt("late-default"));
                return super.getDefault();
            }
        });
        com.bencodez.advancedcore.api.user.usercache.UserDataCache cache=new com.bencodez.advancedcore.api.user.usercache.UserDataCache(f.manager,id);
        cache.cache();assertTrue(registered.get());assertNull(cache.getCachedValue("late-default"));
        cache.cache();assertEquals(0,cache.getCachedValue("late-default").getInt());
    }
    @Test void registeredTypeReadsShareRegistrationMonitor() throws Exception {
        Fixture f=new Fixture();f.manager.addKey(new UserDataKeyInt("typed-int"));f.manager.addKey(new UserDataKeyBoolean("typed-boolean"));
        for(boolean integer:new boolean[]{true,false}) {
            AtomicBoolean value=new AtomicBoolean();Thread reader=new Thread(()->value.set(integer?f.manager.isInt("typed-int"):f.manager.isBoolean("typed-boolean")),"registered-type-reader");
            try {
                synchronized(f.manager) {
                    reader.start();long bound=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                    while(reader.getState()!=Thread.State.BLOCKED && reader.isAlive() && System.nanoTime()<bound)Thread.yield();
                    assertEquals(Thread.State.BLOCKED,reader.getState(),"Type lookup shares registration monitor");
                }
            } finally {reader.join(2000);}
            assertFalse(reader.isAlive());assertTrue(value.get());
        }
    }
    private static class Fixture {
        final AdvancedCorePlugin plugin=mock(AdvancedCorePlugin.class);final UserDataManager manager;
        Fixture() throws Exception {manager=new UserDataManager(plugin);manager.getTimer().shutdownNow();assertTrue(manager.getTimer().awaitTermination(2,TimeUnit.SECONDS));UserManager users=mock(UserManager.class);when(plugin.getUserManager()).thenReturn(users);when(users.getDataManager()).thenReturn(manager);}
    }
}

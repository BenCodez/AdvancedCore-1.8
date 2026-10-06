package com.bencodez.advancedcore.tests.artifact;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.simpleapi.sql.Column;
import com.bencodez.simpleapi.sql.data.*;

class LegacyMySQLCheckedWriteArtifactIT {
    @Test void checkedStatementBindsLegacyTypesAndPublishesIdentityAfterSuccess() throws Exception {
        try (Fixture f=new Fixture()) {
            f.write(Arrays.asList(new Column("PlayerName",new DataValueString("O'Brien")),
                new Column("Enabled",new DataValueBoolean(true))));
            verify(f.connection).prepareStatement("INSERT INTO users (`uuid`, `PlayerName`, `Enabled`) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE `PlayerName`=VALUES(`PlayerName`), `Enabled`=VALUES(`Enabled`);");
            verify(f.statement).setString(1,f.uuid);verify(f.statement).setObject(2,"O'Brien");
            verify(f.statement).setObject(3,"true");verify(f.statement).executeUpdate();
            verify(f.statement).close();verify(f.connection).close();
            assertTrue(f.uuids.contains(f.uuid));assertTrue(f.names.contains("O'Brien"));
        }
    }
    @Test void failedStatementPropagatesAndDoesNotPublishIdentity() throws Exception {
        try (Fixture f=new Fixture()) {
            SQLException failure=new SQLException("write rejected");
            when(f.statement.executeUpdate()).thenThrow(failure);
            InvocationTargetException result=assertThrows(InvocationTargetException.class,
                ()->f.write(Collections.singletonList(new Column("PlayerName",new DataValueString("O'Brien")))));
            assertSame(failure,result.getCause());assertTrue(f.uuids.isEmpty());assertTrue(f.names.isEmpty());
            verify(f.statement).close();verify(f.connection).close();
        }
    }
    @Test void uncommittedConnectionCannotProduceAcknowledgedWrite() throws Exception {
        try (Fixture f=new Fixture()) {
            when(f.connection.getAutoCommit()).thenReturn(false);
            InvocationTargetException result=assertThrows(InvocationTargetException.class,
                ()->f.write(Collections.singletonList(new Column("PlayerName",new DataValueString("LegacyPlayer")))));
            assertTrue(result.getCause() instanceof SQLException);
            verify(f.connection,never()).prepareStatement(anyString());verify(f.connection).close();
            assertTrue(f.uuids.isEmpty());
        }
    }
    @Test void emptyWriteAndPrimaryMutationCannotReachTheConnection() throws Exception {
        try (Fixture f=new Fixture()) {
            f.write(Collections.emptyList());
            InvocationTargetException result=assertThrows(InvocationTargetException.class,
                ()->f.write(Collections.singletonList(new Column("UUID",new DataValueString("other")))));
            assertTrue(result.getCause() instanceof IllegalArgumentException);
            verifyNoInteractions(f.connection,f.statement);
        }
    }
    @Test void unavailableConnectionProducesCheckedFailureWithoutPublishingIdentity() throws Exception {
        try (Fixture f=new Fixture()) {
            f.borrowed.set(null);
            InvocationTargetException result=assertThrows(InvocationTargetException.class,
                ()->f.write(Collections.singletonList(new Column("PlayerName",new DataValueString("LegacyPlayer")))));
            assertTrue(result.getCause() instanceof SQLException);
            assertEquals("MySQL connection is unavailable",result.getCause().getMessage());
            assertTrue(f.uuids.isEmpty());assertTrue(f.names.isEmpty());
            verifyNoInteractions(f.connection,f.statement);
        }
    }
    @Test void checkedReadFailurePropagatesAndClosesBorrowedResources() throws Exception {
        try(Fixture f=new Fixture()) {
            SQLException unavailable=new SQLException("query rejected");when(f.statement.executeQuery()).thenThrow(unavailable);
            InvocationTargetException result=assertThrows(InvocationTargetException.class,
                ()->f.storeType.getMethod("getExactStrict",String.class).invoke(f.store,f.uuid));
            assertSame(unavailable,result.getCause());verify(f.statement).setString(1,f.uuid);
            verify(f.statement).close();verify(f.connection).close();assertTrue(f.uuids.isEmpty());
        }
    }
    @Test void checkedReadRejectsMissingConnectionAndOuterTransaction() throws Exception {
        try(Fixture f=new Fixture()) {
            f.borrowed.set(null);InvocationTargetException missing=assertThrows(InvocationTargetException.class,
                ()->f.storeType.getMethod("getExactStrict",String.class).invoke(f.store,f.uuid));
            assertTrue(missing.getCause() instanceof SQLException);verifyNoInteractions(f.statement,f.connection);
            f.borrowed.set(f.connection);when(f.connection.getAutoCommit()).thenReturn(false);
            InvocationTargetException transaction=assertThrows(InvocationTargetException.class,
                ()->f.storeType.getMethod("getExactStrict",String.class).invoke(f.store,f.uuid));
            assertTrue(transaction.getCause() instanceof SQLException);verify(f.connection).close();verifyNoInteractions(f.statement);
        }
    }
    @Test void committedDeleteEvictsBothIdentityCaches() throws Exception {
        try (Fixture f = new Fixture()) {
            f.uuids.add(f.uuid); f.names.add("old"); f.storeType.getMethod("deletePlayerStrict", String.class).invoke(f.store, f.uuid);
            verify(f.connection).prepareStatement("DELETE FROM users WHERE uuid=?;"); verify(f.statement).setString(1, f.uuid);
            verify(f.statement).executeUpdate(); verify(f.statement).close(); verify(f.connection).close();
            assertTrue(f.uuids.isEmpty()); assertTrue(f.names.isEmpty());
        }
    }
    @Test void strictDeletePropagatesFailureAndRetainsIdentityCaches() throws Exception {
        try (Fixture f = new Fixture()) {
            f.uuids.add(f.uuid); f.names.add("old"); SQLException failure = new SQLException("delete rejected"); when(f.statement.executeUpdate()).thenThrow(failure);
            InvocationTargetException result = assertThrows(InvocationTargetException.class, () -> f.storeType.getMethod("deletePlayerStrict", String.class).invoke(f.store, f.uuid));
            assertSame(failure, result.getCause().getCause()); assertTrue(f.uuids.contains(f.uuid)); assertTrue(f.names.contains("old"));
            verify(f.statement).close(); verify(f.connection).close();
        }
    }
    @Test void checkedDeleteRejectsOuterTransactionAndMissingConnection() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.connection.getAutoCommit()).thenReturn(false);
            InvocationTargetException result = assertThrows(InvocationTargetException.class, () -> f.storeType.getMethod("deletePlayerStrict", String.class).invoke(f.store, f.uuid));
            assertTrue(result.getCause().getCause() instanceof SQLException); verify(f.connection).close(); verifyNoInteractions(f.statement);
            f.borrowed.set(null); assertThrows(InvocationTargetException.class, () -> f.storeType.getMethod("deletePlayerStrict", String.class).invoke(f.store, f.uuid));
        }
    }
    @Test void postDeleteConnectionCloseFailureIsVisibleAndCannotPublishAcknowledgedRemoval() throws Exception {
        try (Fixture f = new Fixture()) {
            f.uuids.add(f.uuid); f.names.add("old"); SQLException uncertain = new SQLException("connection cleanup failed"); doThrow(uncertain).when(f.connection).close();
            InvocationTargetException result = assertThrows(InvocationTargetException.class, () -> f.storeType.getMethod("deletePlayerStrict", String.class).invoke(f.store, f.uuid));
            assertSame(uncertain, result.getCause().getCause()); verify(f.statement).executeUpdate(); verify(f.statement).close();
            assertTrue(f.uuids.contains(f.uuid)); assertTrue(f.names.contains("old"));
        }
    }
    private static class Fixture implements AutoCloseable {
        final String uuid="00000000-0000-0000-0000-000000000002";
        final Connection connection=mock(Connection.class);
        final PreparedStatement statement=mock(PreparedStatement.class);
        final Set<String> uuids=new HashSet<>();
        final Set<String> names=new HashSet<>();
        final URLClassLoader loader;
        final Class<?> storeType;
        final Object store;
        final Object manager;
        final AtomicReference<Connection> borrowed=new AtomicReference<>(connection);
        Fixture() throws Exception {
            URL artifact=Paths.get(System.getProperty("advancedcore.jar")).toUri().toURL();
            loader=new URLClassLoader(new URL[]{artifact},getClass().getClassLoader()) {
                private boolean child(String name) {
                    return name.startsWith("com.bencodez.advancedcore.api.user.userstorage.mysql.MySQL")
                        ||name.startsWith("com.bencodez.simpleapi.sql.mysql.")
                        ||name.startsWith("com.bencodez.advancedcore.hikari.");
                }
                @Override public URL getResource(String name) {
                    if(child(name.replace('/','.'))) {URL resource=findResource(name);if(resource!=null)return resource;}
                    return super.getResource(name);
                }
                @Override protected synchronized Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {
                    if(child(name)) {
                        Class<?> type=findLoadedClass(name);if(type==null)type=findClass(name);
                        if(resolve)resolveClass(type);return type;
                    }
                    return super.loadClass(name,resolve);
                }
            };
            storeType=loader.loadClass("com.bencodez.advancedcore.api.user.userstorage.mysql.MySQL");
            store=mock(storeType,withSettings().defaultAnswer(CALLS_REAL_METHODS));
            Class<?> driverType=loader.loadClass("com.bencodez.simpleapi.sql.mysql.MySQL");
            manager=mock(loader.loadClass("com.bencodez.simpleapi.sql.mysql.ConnectionManager"),
                withSettings().defaultAnswer(call ->
                    call.getMethod().getName().equals("getConnection") ? borrowed.get() : RETURNS_DEFAULTS.answer(call)));
            Object driver=mock(driverType,withSettings().defaultAnswer(call ->
                call.getMethod().getName().equals("getConnectionManager") ? manager : RETURNS_DEFAULTS.answer(call)));
            when(connection.getAutoCommit()).thenReturn(true);when(connection.prepareStatement(anyString())).thenReturn(statement);
            set("name","users");set("plugin",mock(AdvancedCorePlugin.class));set("mysql",driver);
            set("object2",new Object());set("object4",new Object());set("columns",new ArrayList<>(Arrays.asList("PlayerName","Enabled")));
            set("uuids",uuids);set("names",names);
        }
        void write(List<Column> values)throws Exception {storeType.getMethod("updateStrict",String.class,List.class).invoke(store,uuid,values);}
        void set(String name,Object value)throws Exception {Field field=storeType.getDeclaredField(name);field.setAccessible(true);field.set(store,value);}
        public void close()throws Exception {loader.close();}
    }
}

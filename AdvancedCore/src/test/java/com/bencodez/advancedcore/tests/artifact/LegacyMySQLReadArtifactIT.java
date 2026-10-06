package com.bencodez.advancedcore.tests.artifact;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.AdvancedCorePlugin;

class LegacyMySQLReadArtifactIT {
    @Test void sqlLookingPlayerNameCannotMatchAnotherPlayer() throws Exception {
        assertLookup("' OR 1=1 --",null);
    }
    @Test void apostropheInStoredPlayerNameRemainsSearchable() throws Exception {
        assertLookup("O'Brien","00000000-0000-0000-0000-000000000001");
    }
    private void assertLookup(String name,String expected) throws Exception {
        // Exercise the packaged Java8 pool relocation rather than the upstream
        // dependency's unshaded Java11 pool on the unit-test classpath.
        URL artifact=Paths.get(System.getProperty("advancedcore.jar")).toUri().toURL();
        try(URLClassLoader loader=new URLClassLoader(new URL[]{artifact},getClass().getClassLoader()) {
            @Override public URL getResource(String name) {
                if (name.startsWith("com/bencodez/advancedcore/api/user/userstorage/mysql/MySQL")
                        || name.startsWith("com/bencodez/simpleapi/sql/mysql/")
                        || name.startsWith("com/bencodez/advancedcore/hikari/")) {
                    URL resource = findResource(name);
                    if (resource != null) return resource;
                }
                return super.getResource(name);
            }
            @Override protected synchronized Class<?> loadClass(String n,boolean resolve)throws ClassNotFoundException {
                if(n.startsWith("com.bencodez.advancedcore.api.user.userstorage.mysql.MySQL")
                        ||n.startsWith("com.bencodez.simpleapi.sql.mysql.")
                        ||n.startsWith("com.bencodez.advancedcore.hikari.")) {
                    Class<?> type=findLoadedClass(n);
                    if(type==null)type=findClass(n);
                    if(resolve)resolveClass(type);
                    return type;
                }
                return super.loadClass(n,resolve);
            }
        }) {
            Class.forName("org.sqlite.JDBC");
            try(Connection connection=DriverManager.getConnection("jdbc:sqlite::memory:")) {
                try(java.sql.Statement statement=connection.createStatement()) {
                    statement.executeUpdate("CREATE TABLE users (uuid TEXT, PlayerName TEXT)");
                    statement.executeUpdate("INSERT INTO users VALUES ('00000000-0000-0000-0000-000000000001', 'O''Brien')");
                }
                Class<?> storeType=loader.loadClass("com.bencodez.advancedcore.api.user.userstorage.mysql.MySQL");
                Object store=mock(storeType,withSettings().defaultAnswer(CALLS_REAL_METHODS));
                Class<?> driverType=loader.loadClass("com.bencodez.simpleapi.sql.mysql.MySQL");
                Object driver=mock(driverType,withSettings());
                Object manager=mock(loader.loadClass("com.bencodez.simpleapi.sql.mysql.ConnectionManager"),
                        withSettings());
                when(driverType.getMethod("getConnectionManager").invoke(driver)).thenReturn(manager);
                when((Connection)manager.getClass().getMethod("getConnection").invoke(manager)).thenReturn(connection);
                set(storeType,store,"name","users");
                set(storeType,store,"plugin",mock(AdvancedCorePlugin.class));
                set(storeType,store,"mysql",driver);
                assertEquals(expected,storeType.getMethod("getUUID",String.class).invoke(store,name));
                assertTrue(connection.isClosed(),"The read must close its connection");
            }
        }
    }
    private void set(Class<?> type,Object target,String name,Object value)throws Exception {
        Field f=type.getDeclaredField(name);f.setAccessible(true);f.set(target,value);
    }
}

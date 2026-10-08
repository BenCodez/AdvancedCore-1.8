package com.bencodez.advancedcore.tests.artifact;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.Paths;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.api.user.UserStorage;

/** Tests the packaged Java 8 pool, not the incompatible compile-time Hikari copy. */
class LegacySharedMysqlFactoryArtifactIT {
    @Test void checkedBorrowClosesConnectionWithoutClosingOrReopeningPool() throws Exception {
        try (Fixture f = new Fixture()) {
            assertTrue(f.contains());
            verify(f.connection).close(); assertEquals(1, f.borrows);
            assertEquals(0, f.legacyBorrows); assertEquals(0, f.reopens); assertEquals(0, f.poolCloses);
        }
    }
    @Test void closedOrMissingPoolIsFailureAndCannotResurrectProvider() throws Exception {
        try (Fixture f = new Fixture()) {
            f.closed.set(true); assertTrue(f.failure().getCause() instanceof SQLException);
            f.closed.set(false); f.poolAvailable = false; assertTrue(f.failure().getCause() instanceof SQLException);
            assertEquals(0, f.borrows); assertEquals(0, f.legacyBorrows); assertEquals(0, f.reopens);
            verifyNoInteractions(f.connection);
        }
    }
    @Test void checkedAcquisitionRetainsExactCauseAndDoesNotRetry() throws Exception {
        try (Fixture f = new Fixture()) {
            SQLException cause = new SQLException("borrow rejected"); f.borrowFailure = cause;
            assertSame(cause, f.failure().getCause()); assertEquals(1, f.borrows);
            assertEquals(0, f.reopens); assertEquals(0, f.legacyBorrows); assertEquals(0, f.poolCloses);
        }
    }
    @Test void missingManagerOrNullBorrowDoesNotBecomeAnEmptyRead() throws Exception {
        try (Fixture f = new Fixture()) {
            f.managerAvailable = false; assertTrue(f.failure().getCause() instanceof SQLException);
            f.managerAvailable = true; f.nullBorrow = true; assertTrue(f.failure().getCause() instanceof SQLException);
            assertEquals(1, f.borrows); assertEquals(0, f.reopens); assertEquals(0, f.legacyBorrows);
        }
    }

    @Test void getterFailureIsCheckedAndCannotFallBackToReopening() throws Exception {
        try (Fixture f = new Fixture()) {
            IllegalStateException cause = new IllegalStateException("pool getter failed"); f.getterFailure = cause;
            Throwable failure = f.failure(); assertTrue(failure.getCause() instanceof SQLException);
            assertSame(cause, failure.getCause().getCause().getCause());
            assertEquals(0, f.borrows); assertEquals(0, f.legacyBorrows); assertEquals(0, f.reopens);
        }
    }
    @Test void postgresSelectionIsRejectedBeforeLegacyProviderAccess() throws Exception {
        try (Fixture f = new Fixture()) {
            Class<?> factory = f.loader.loadClass("com.bencodez.advancedcore.core.user.storage.sql.SqlUserBackendFactory");
            Class<?> schema = f.loader.loadClass("com.bencodez.advancedcore.core.user.storage.sql.SqlUserSchema");
            Class<?> mysql = f.loader.loadClass("com.bencodez.simpleapi.sql.mysql.MySQL");
            Class<?> type = f.loader.loadClass(factory.getName() + "$DatabaseType");
            Class<?> logger = f.loader.loadClass("com.bencodez.advancedcore.core.user.storage.sql.SqlBackendLogger");
            Object provider = mock(mysql);
            InvocationTargetException failure = assertThrows(InvocationTargetException.class, () -> factory
                    .getMethod("existingMysqlUser", UUID.class, String.class, schema, mysql, type, logger)
                    .invoke(null, UUID.randomUUID(), "Users", null, provider, type.getEnumConstants()[2], null));
            assertTrue(failure.getCause() instanceof IllegalArgumentException); verifyNoInteractions(provider);
        }
    }

    static final class Fixture implements AutoCloseable {
        final Connection connection = mock(Connection.class);
        final URLClassLoader loader;
        final AtomicBoolean closed = new AtomicBoolean();
        boolean poolAvailable = true, managerAvailable = true, nullBorrow;
        SQLException borrowFailure;
        RuntimeException getterFailure;
        int borrows, legacyBorrows, reopens, poolCloses;
        final Object user;
        Fixture() throws Exception {
            loader = new URLClassLoader(new URL[] {Paths.get(System.getProperty("advancedcore.jar")).toUri().toURL()}, getClass().getClassLoader()) {
                boolean child(String name) { return name.startsWith("com.bencodez.advancedcore.core.user.storage.")
                        || name.startsWith("com.bencodez.simpleapi.sql.mysql.") || name.startsWith("com.bencodez.advancedcore.hikari."); }
                @Override public URL getResource(String name) {
                    if (child(name.replace('/', '.'))) {URL found = findResource(name); if (found != null) return found;}
                    return super.getResource(name);
                }
                @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (child(name)) {Class<?> found = findLoadedClass(name); if (found == null) found = findClass(name); if (resolve) resolveClass(found); return found;}
                    return super.loadClass(name, resolve);
                }
            };
            Class<?> poolType = loader.loadClass("com.bencodez.advancedcore.hikari.HikariDataSource");
            Object pool = mock(poolType, withSettings().defaultAnswer(call -> {
                switch (call.getMethod().getName()) {
                    case "isClosed": return closed.get();
                    case "getConnection": borrows++; if (borrowFailure != null) throw borrowFailure; return nullBorrow ? null : connection;
                    case "close": poolCloses++; return null;
                    default: return RETURNS_DEFAULTS.answer(call);
                }
            }));
            Object manager = mock(loader.loadClass("com.bencodez.simpleapi.sql.mysql.ConnectionManager"), withSettings().defaultAnswer(call -> {
                switch (call.getMethod().getName()) {
                    case "getDataSource": if (getterFailure != null) throw getterFailure; return poolAvailable ? pool : null;
                    case "isClosed": return closed.get();
                    case "getConnection": legacyBorrows++; return connection;
                    case "open": reopens++; return true;
                    default: return RETURNS_DEFAULTS.answer(call);
                }
            }));
            Class<?> mysqlType = loader.loadClass("com.bencodez.simpleapi.sql.mysql.MySQL");
            Object mysql = mock(mysqlType, withSettings().defaultAnswer(call -> call.getMethod().getName().equals("getConnectionManager")
                    ? (managerAvailable ? manager : null) : RETURNS_DEFAULTS.answer(call)));
            PreparedStatement statement = mock(PreparedStatement.class); ResultSet result = mock(ResultSet.class);
            when(result.next()).thenReturn(true); when(statement.executeQuery()).thenReturn(result);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            Class<?> schemaType = loader.loadClass("com.bencodez.advancedcore.core.user.storage.sql.SqlUserSchema");
            Object builder = schemaType.getMethod("builder").invoke(null), schema = builder.getClass().getMethod("build").invoke(builder);
            Class<?> loggerType = loader.loadClass("com.bencodez.advancedcore.core.user.storage.sql.SqlBackendLogger");
            Class<?> factory = loader.loadClass("com.bencodez.advancedcore.core.user.storage.sql.SqlUserBackendFactory");
            Class<?> databaseType = loader.loadClass(factory.getName() + "$DatabaseType");
            Object type = databaseType.getEnumConstants()[0];
            user = factory.getMethod("existingMysqlUser", UUID.class, String.class, schemaType, mysqlType, databaseType, loggerType)
                    .invoke(null, UUID.fromString("c17d7784-00ce-421f-a38b-a30ed419e1a4"), "Users", schema, mysql, type, loggerType.getField("NO_OP").get(null));
            assertEquals(0, borrows); assertEquals(0, legacyBorrows);
        }
        boolean contains() throws Exception {
            Class<?> api = loader.loadClass("com.bencodez.advancedcore.core.user.storage.SqlUserStorage");
            return (Boolean) api.getMethod("contains", UserStorage.class).invoke(user, UserStorage.MYSQL);
        }
        Throwable failure() {return assertThrows(InvocationTargetException.class, this::contains).getCause();}
        public void close() throws Exception {loader.close();}
    }
}

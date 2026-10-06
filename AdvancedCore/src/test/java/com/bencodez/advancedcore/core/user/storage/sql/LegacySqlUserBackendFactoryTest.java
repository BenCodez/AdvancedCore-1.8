package com.bencodez.advancedcore.core.user.storage.sql;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyString;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.bencodez.advancedcore.api.user.UserStorage;
import com.bencodez.advancedcore.api.user.usercache.keys.UserDataKey;
import com.bencodez.advancedcore.api.user.usercache.keys.UserDataKeyInt;
import com.bencodez.advancedcore.api.user.usercache.keys.UserDataKeyString;
import com.bencodez.advancedcore.core.user.storage.SqlUserDataAccess;
import com.bencodez.advancedcore.core.user.storage.SqlUserStorage;
import com.bencodez.simpleapi.sql.data.DataValue;
import com.bencodez.simpleapi.sql.data.DataValueInt;

class LegacySqlUserBackendFactoryTest {
    @TempDir Path directory;
    private static final UUID USER = UUID.fromString("c17d7784-00ce-421f-a38b-a30ed419e1a4");

    @Test void factoryCreatesTypedSchemaAndBorrowedTransactionDoesNotRetireOwner() throws Exception {
        java.util.List<UserDataKey> keys = Arrays.asList(new UserDataKeyString("PlayerName"), new UserDataKeyInt("Points"));
        try (SqliteUserBackend backend = SqlUserBackendFactory.sqlite(directory, "Users", "Users", keys, SqlBackendLogger.NO_OP)) {
            AtomicInteger borrows = new AtomicInteger();
            SqlUserStorage user = SqlUserBackendFactory.existingUser(UserStorage.SQLITE, USER, "Users",
                    SqlUserSchema.fromKeys(keys), () -> {
                        borrows.incrementAndGet();
                        return DriverManager.getConnection("jdbc:sqlite:" + backend.databaseFile());
                    }, null, SqlBackendLogger.NO_OP);
            assertEquals(0, borrows.get());
            assertEquals("committed", user.transaction(UserStorage.SQLITE, scope -> {
                assertTrue(scope.createdUserRow());
                scope.writeValues(Collections.<String, DataValue>singletonMap("Points", new DataValueInt(17)));
                return "committed";
            }));
            assertEquals(1, borrows.get()); assertTrue(backend.isOpen());
            assertEquals(17, new SqlUserDataAccess(backend.user(USER)).getInt(UserStorage.SQLITE, "Points", 0));
            IllegalArgumentException failure = new IllegalArgumentException("caller work failed");
            assertSame(failure, assertThrows(IllegalArgumentException.class, () -> user.transaction(UserStorage.SQLITE, scope -> {
                scope.writeValues(Collections.<String, DataValue>singletonMap("Points", new DataValueInt(99)));
                throw failure;
            })));
            assertEquals(2, borrows.get()); assertTrue(backend.isOpen());
            assertEquals(17, new SqlUserDataAccess(backend.user(USER)).getInt(UserStorage.SQLITE, "Points", 0));
        }
    }

    @Test void explicitDatabaseTypeDeterminesQuotingAndUuidBindingWithoutOpeningAtConstruction() throws Exception {
        for (SqlUserBackendFactory.DatabaseType type : SqlUserBackendFactory.DatabaseType.values()) {
            Connection connection = mock(Connection.class); PreparedStatement statement = mock(PreparedStatement.class);
            ResultSet result = mock(ResultSet.class); when(result.next()).thenReturn(true);
            when(statement.executeQuery()).thenReturn(result); when(connection.prepareStatement(anyString())).thenReturn(statement);
            AtomicInteger borrows = new AtomicInteger();
            SqlUserStorage user = SqlUserBackendFactory.existingUser(UserStorage.MYSQL, USER, "Users", SqlUserSchema.builder().build(),
                    () -> { borrows.incrementAndGet(); return connection; }, type, SqlBackendLogger.NO_OP);
            assertEquals(0, borrows.get()); assertTrue(user.contains(UserStorage.MYSQL)); assertEquals(1, borrows.get());
            if (type == SqlUserBackendFactory.DatabaseType.POSTGRESQL) {
                verify(connection).prepareStatement("SELECT 1 FROM \"Users\" WHERE \"uuid\"=? LIMIT 1");
                verify(statement).setObject(1, USER);
            } else {
                verify(connection).prepareStatement("SELECT 1 FROM `Users` WHERE `uuid`=? LIMIT 1");
                verify(statement).setString(1, USER.toString());
            }
            verify(connection).close();
        }
    }

    @Test void wrongStorageAndMissingProviderOrDatabaseTypeAreRejectedBeforeBorrowing() {
        SqlUserBackendFactory.ConnectionOpener connections = mock(SqlUserBackendFactory.ConnectionOpener.class);
        for (UserStorage storage : UserStorage.values()) {
            if (storage != UserStorage.MYSQL && storage != UserStorage.SQLITE) {
                assertThrows(IllegalArgumentException.class, () -> SqlUserBackendFactory.existingUser(storage, USER, "Users",
                        SqlUserSchema.builder().build(), connections, SqlUserBackendFactory.DatabaseType.MYSQL, SqlBackendLogger.NO_OP));
            }
        }
        assertThrows(NullPointerException.class, () -> SqlUserBackendFactory.existingUser(UserStorage.MYSQL, USER, "Users",
                SqlUserSchema.builder().build(), connections, null, SqlBackendLogger.NO_OP));
        assertThrows(NullPointerException.class, () -> SqlUserBackendFactory.existingUser(UserStorage.SQLITE, USER, "Users",
                SqlUserSchema.builder().build(), null, null, SqlBackendLogger.NO_OP));
        verifyNoInteractions(connections);
    }

    @Test void connectionAcquisitionFailureRetainsSqlCauseWithoutRetry() throws Exception {
        SQLException unavailable = new SQLException("database unavailable"); AtomicInteger attempts = new AtomicInteger();
        SqlUserStorage user = SqlUserBackendFactory.existingUser(UserStorage.MYSQL, USER, "Users", SqlUserSchema.builder().build(),
                () -> { attempts.incrementAndGet(); throw unavailable; }, SqlUserBackendFactory.DatabaseType.MYSQL, SqlBackendLogger.NO_OP);
        assertSame(unavailable, assertThrows(IllegalStateException.class, () -> user.contains(UserStorage.MYSQL)).getCause());
        assertEquals(1, attempts.get());
    }
}

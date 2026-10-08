package com.bencodez.advancedcore.core.user.storage.sql;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;

import com.bencodez.advancedcore.api.user.UserStorage;
import com.bencodez.advancedcore.core.user.storage.SqlUserDataAccess;
import com.bencodez.advancedcore.core.user.storage.SqlUserStorage;
import com.bencodez.simpleapi.sql.DataType;
import com.bencodez.simpleapi.sql.data.DataValue;
import com.bencodez.simpleapi.sql.data.DataValueInt;
import com.bencodez.simpleapi.sql.data.DataValueString;

class LegacyJdbcSqliteTransactionTest {
    @TempDir Path directory;
    private static final UUID USER = UUID.fromString("c17d7784-00ce-421f-a38b-a30ed419e1a4");

    @BeforeAll static void loadLegacyDriver() throws ClassNotFoundException {
        Class.forName("org.sqlite.JDBC");
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("users.db"));
    }

    private JdbcSqlUserStorage setup(UUID uuid) throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS Users (uuid TEXT PRIMARY KEY, Points INTEGER DEFAULT 0, PlayerName TEXT NOT NULL)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS Receipts (id TEXT PRIMARY KEY)");
        }
        return new JdbcSqlUserStorage(UserStorage.SQLITE, uuid, "Users", SqlUserSchema.builder()
                .column("Points", "INTEGER", DataType.INTEGER).column("PlayerName", "TEXT", DataType.STRING)
                .build(), this::open, JdbcSqlUserStorage.Dialect.SQLITE, SqlBackendLogger.NO_OP);
    }

    private int countReceipts() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM Receipts")) {
            assertTrue(result.next()); return result.getInt(1);
        }
    }

    @Test void callerReceiptAndUserUpdateCommitTogetherAndScopeExpires() throws Exception {
        JdbcSqlUserStorage user = setup(USER);
        AtomicReference<SqlUserStorage.TransactionScope> retained = new AtomicReference<>();
        assertEquals("saved", user.transaction(UserStorage.SQLITE,
                Collections.<String, DataValue>singletonMap("PlayerName", new DataValueString("Ben")), scope -> {
                    assertTrue(scope.createdUserRow()); retained.set(scope);
                    try (PreparedStatement statement = scope.connection().prepareStatement("INSERT INTO Receipts(id) VALUES (?)")) {
                        statement.setString(1, "vote-one"); statement.executeUpdate();
                    }
                    scope.writeValues(Collections.<String, DataValue>singletonMap("Points", new DataValueInt(17)));
                    assertEquals(17, SqlUserDataAccess.convert(scope.readRow()).get("Points").getInt());
                    return "saved";
                }));
        assertEquals(1, countReceipts());
        assertEquals(17, SqlUserDataAccess.convert(user.readRow(UserStorage.SQLITE)).get("Points").getInt());
        assertThrows(IllegalStateException.class, () -> retained.get().connection());
        assertThrows(IllegalStateException.class, () -> retained.get().readRow());
        assertThrows(IllegalStateException.class, () -> retained.get().writeValues(Collections.emptyMap()));
        assertThrows(IllegalStateException.class, () -> retained.get().createdUserRow());
        assertFalse(user.transaction(UserStorage.SQLITE, SqlUserStorage.TransactionScope::createdUserRow));
    }

    @Test void failedCallerWorkRollsBackNewUserAndReceipt() throws Exception {
        JdbcSqlUserStorage user = setup(USER);
        SQLException cause = new SQLException("caller work failed");
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> user.transaction(UserStorage.SQLITE,
                Collections.<String, DataValue>singletonMap("PlayerName", new DataValueString("Ben")), scope -> {
                    scope.writeValues(Collections.<String, DataValue>singletonMap("Points", new DataValueInt(8)));
                    try (Statement statement = scope.connection().createStatement()) {
                        statement.executeUpdate("INSERT INTO Receipts(id) VALUES ('vote-one')");
                    }
                    throw cause;
                }));
        assertSame(cause, failure.getCause()); assertFalse(user.contains(UserStorage.SQLITE)); assertEquals(0, countReceipts());
    }

    @Test void failedExistingUserTransactionPreservesOriginalValuesAndIgnoresSeed() throws Exception {
        JdbcSqlUserStorage user = setup(USER);
        user.write(UserStorage.SQLITE, "PlayerName", new DataValueString("Original"));
        user.write(UserStorage.SQLITE, "Points", new DataValueInt(17));
        IllegalArgumentException cause = new IllegalArgumentException("caller failed");
        assertSame(cause, assertThrows(IllegalArgumentException.class, () -> user.transaction(UserStorage.SQLITE,
                Collections.<String, DataValue>singletonMap("PlayerName", new DataValueString("Replacement")), scope -> {
                    assertFalse(scope.createdUserRow());
                    assertEquals("Original", SqlUserDataAccess.convert(scope.readRow()).get("PlayerName").getString());
                    scope.writeValues(Collections.<String, DataValue>singletonMap("Points", new DataValueInt(3)));
                    throw cause;
                })));
        assertEquals(17, SqlUserDataAccess.convert(user.readRow(UserStorage.SQLITE)).get("Points").getInt());
        assertEquals("Original", SqlUserDataAccess.convert(user.readRow(UserStorage.SQLITE)).get("PlayerName").getString());
    }

    @Test void retainedQuotedTableAndUnicodeBooleanRepresentationRemainReadable() throws Exception {
        String table = "User `Data`";
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE " + JdbcSqlUserStorage.Dialect.SQLITE.quote(table)
                    + " (uuid TEXT PRIMARY KEY, Flag TEXT)");
        }
        JdbcSqlUserStorage user = new JdbcSqlUserStorage(UserStorage.SQLITE, USER, table, SqlUserSchema.builder()
                .column("Flag", "TEXT", DataType.BOOLEAN).build(), this::open,
                JdbcSqlUserStorage.Dialect.SQLITE, SqlBackendLogger.NO_OP);
        user.write(UserStorage.SQLITE, "Flag", new DataValueString("\u2003true\u2003"));
        assertTrue(SqlUserDataAccess.convert(user.readRow(UserStorage.SQLITE)).get("Flag").getBoolean());
        HashMap<String, DataValue> values = new HashMap<>(); values.put("UUID", new DataValueString(UUID.randomUUID().toString()));
        values.put("Flag", new DataValueString("false")); user.writeValues(UserStorage.SQLITE, values);
        assertEquals(USER.toString(), SqlUserDataAccess.convert(user.readRow(UserStorage.SQLITE)).get("uuid").getString());
        assertFalse(SqlUserDataAccess.convert(user.readRow(UserStorage.SQLITE)).get("Flag").getBoolean());
        user.delete(UserStorage.SQLITE); assertFalse(user.contains(UserStorage.SQLITE));
    }
}

package com.bencodez.advancedcore.tests.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.user.userstorage.sql.UserTable;
import com.bencodez.simpleapi.sql.Column;
import com.bencodez.simpleapi.sql.DataType;
import com.bencodez.simpleapi.sql.data.DataValueString;
import com.bencodez.simpleapi.sql.sqlite.db.SQLite;

class LegacySQLiteBoundValueTest {
    @org.junit.jupiter.api.BeforeAll static void loadLegacyDriver() throws Exception { Class.forName("org.sqlite.JDBC"); }
    @Test void quotedPlayerDataUpdatesWithoutChangingAnotherUser() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            UserTable table = fixture(connection);
            table.update(new Column("uuid", new DataValueString("target")),
                new ArrayList<>(Collections.singletonList(new Column("PlayerName", new DataValueString("O'Brien")))));
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT PlayerName FROM Users WHERE uuid='target'")) {
                assertTrue(rows.next()); assertEquals("O'Brien", rows.getString(1));
            }
            assertEquals("other", table.getUUID("OtherPlayer"));
        }
    }
    @Test void lookupTreatsSqlLookingNamesAsLiteralData() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            UserTable table = fixture(connection);
            assertNull(table.getUUID("' OR 1=1 --"));
            assertEquals("target", table.getUUID("OriginalPlayer"));
        }
    }
    private UserTable fixture(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE Users (uuid TEXT PRIMARY KEY, PlayerName TEXT)");
            statement.executeUpdate("INSERT INTO Users VALUES ('target','OriginalPlayer')");
            statement.executeUpdate("INSERT INTO Users VALUES ('other','OtherPlayer')");
        }
        SQLite sqlite = mock(SQLite.class);
        when(sqlite.getSQLConnection()).thenReturn(connection);
        UserTable table = new UserTable(mock(AdvancedCorePlugin.class),"Users",Arrays.asList(
                new Column("uuid",DataType.STRING),new Column("PlayerName",DataType.STRING)));
        table.setSqLite(sqlite); return table;
    }
}

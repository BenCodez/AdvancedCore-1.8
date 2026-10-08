package com.bencodez.advancedcore.tests.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.user.usercache.keys.UserDataKeyInt;
import com.bencodez.advancedcore.api.user.userstorage.sql.UserTable;
import com.bencodez.simpleapi.sql.Column;
import com.bencodez.simpleapi.sql.DataType;
import com.bencodez.simpleapi.sql.sqlite.db.SQLite;

class LegacySQLiteColumnMigrationTest {
    @Test void reservedColumnNamesMigrateOnceWithoutLosingExistingRows() throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE users (uuid VARCHAR(100) PRIMARY KEY)");
                statement.executeUpdate("INSERT INTO users(uuid) VALUES ('existing-user')");
            }
            SQLite sqlite = mock(SQLite.class);
            when(sqlite.getSQLConnection()).thenReturn(connection);
            doAnswer(call -> {
                if (call.getArgument(1) != null) ((java.sql.ResultSet) call.getArgument(1)).close();
                if (call.getArgument(0) != null) ((java.sql.PreparedStatement) call.getArgument(0)).close();
                return null;
            }).when(sqlite).close(any(), any());
            UserTable table = new UserTable(mock(AdvancedCorePlugin.class), "users",
                    new ArrayList<>(Arrays.asList(new Column("uuid", DataType.STRING))));
            table.setSqLite(sqlite);
            table.addColoumn(new Column("select", DataType.INTEGER));
            table.addColoumn(new Column("select", DataType.INTEGER));
            table.addColoumn(new UserDataKeyInt("order"));
            table.addColoumn(new UserDataKeyInt("order"));
            assertEquals(Arrays.asList("uuid", "select", "order"), table.getTableColumns());
            try (Statement statement = connection.createStatement();
                 java.sql.ResultSet result = statement.executeQuery("SELECT uuid, `order` FROM users")) {
                assertTrue(result.next());
                assertEquals("existing-user", result.getString(1));
                assertEquals(0, result.getInt(2));
                assertFalse(result.next());
            }
        }
    }
}

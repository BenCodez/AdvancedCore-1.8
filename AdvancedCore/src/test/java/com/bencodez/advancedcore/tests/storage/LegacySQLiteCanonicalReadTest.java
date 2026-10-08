package com.bencodez.advancedcore.tests.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.user.UserData;
import com.bencodez.advancedcore.api.user.usercache.UserDataManager;
import com.bencodez.advancedcore.api.user.usercache.keys.UserDataKeyInt;
import com.bencodez.advancedcore.api.user.userstorage.sql.UserTable;
import com.bencodez.simpleapi.sql.*;
import com.bencodez.simpleapi.sql.data.*;
import com.bencodez.simpleapi.sql.sqlite.db.SQLite;

class LegacySQLiteCanonicalReadTest {
    @Test void realRetainedColumnIsCanonicalForStrictLegacyAndConversionReads() throws Exception {
        Class.forName("org.sqlite.JDBC");
        UUID id=UUID.fromString("8d17c3ab-1085-4e22-95ad-8c975f3e4c24");
        try(Connection connection=DriverManager.getConnection("jdbc:sqlite::memory:")) {
            try(Statement statement=connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE Users (UUID TEXT PRIMARY KEY, points INTEGER, Custom TEXT)");
                statement.executeUpdate("INSERT INTO Users VALUES ('"+id+"',17,'raw')");
            }
            AdvancedCorePlugin plugin=mock(AdvancedCorePlugin.class,RETURNS_DEEP_STUBS);
            UserDataManager types=mock(UserDataManager.class);
            when(plugin.getUserManager().getDataManager()).thenReturn(types);
            when(types.getRegisteredKeysSnapshot()).thenReturn(new ArrayList<>(Collections.singletonList(new UserDataKeyInt("Points"))));
            when(types.isInt("Points")).thenReturn(true);
            SQLite sqlite=mock(SQLite.class);when(sqlite.getSQLConnection()).thenReturn(connection);
            UserTable table=new UserTable(plugin,"Users",Collections.singletonList(new Column("uuid",DataType.STRING)));
            table.setSqLite(sqlite);Column primary=new Column("uuid",new DataValueString(id.toString()));
            for(List<Column> row:Arrays.asList(table.getExactStrict(primary),table.getExact(primary),
                    table.getAllQueryStrict().get(id),table.getAllQuery().get(id))) {
                Map<String,DataValue> converted=new UserData(null).convert(row);
                assertEquals(17,converted.get("Points").getInt());assertFalse(converted.containsKey("points"));
                assertEquals(id.toString(),converted.get("uuid").getString());assertEquals("raw",converted.get("Custom").getString());
            }
            verify(types,times(4)).getRegisteredKeysSnapshot();assertFalse(connection.isClosed());
        }
    }
}

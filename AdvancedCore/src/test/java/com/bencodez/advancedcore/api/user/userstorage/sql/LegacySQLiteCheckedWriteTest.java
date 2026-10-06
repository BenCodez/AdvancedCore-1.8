package com.bencodez.advancedcore.api.user.userstorage.sql;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.simpleapi.sql.Column;
import com.bencodez.simpleapi.sql.data.*;
import com.bencodez.simpleapi.sql.sqlite.db.SQLite;

class LegacySQLiteCheckedWriteTest {
    @Test void checkedUpdatePreservesUnrelatedColumnsAndLegacyBooleanText() throws Exception {
        try (Connection connection=database()) {
            try (Statement setup=connection.createStatement()) {
                setup.executeUpdate("INSERT INTO users VALUES ('one','old','keep','false')");
            }
            UserTable table=fixture(connection);
            table.updateStrict(primary("one"),Arrays.asList(new Column("Message",new DataValueString("O'Brien")),
                new Column("Enabled",new DataValueBoolean(true))));
            try (Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery("SELECT * FROM users")) {
                assertTrue(rows.next());assertEquals("O'Brien",rows.getString("Message"));
                assertEquals("keep",rows.getString("Preserved"));assertEquals("true",rows.getString("Enabled"));
                assertFalse(rows.next());
            }
            assertFalse(connection.isClosed());
        }
    }
    @Test void newIdentityIsInsertedAndIdempotentRetryUpdatesTheSameRow() throws Exception {
        try (Connection connection=database()) {
            UserTable table=fixture(connection);
            List<Column> values=Collections.singletonList(new Column("Message",new DataValueString("quoted'value")));
            table.updateStrict(primary("new"),values);table.updateStrict(primary("new"),values);
            try (Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery("SELECT * FROM users")) {
                assertTrue(rows.next());assertEquals("new",rows.getString("uuid"));
                assertEquals("quoted'value",rows.getString("Message"));assertFalse(rows.next());
            }
            assertEquals(1,values.size());
        }
    }
    @Test void rejectedBatchPropagatesWithoutWritingItsOtherColumns() throws Exception {
        try (Connection connection=database()) {
            try (Statement setup=connection.createStatement()) {
                setup.executeUpdate("INSERT INTO users VALUES ('one','old','keep','false')");
            }
            UserTable table=fixture(connection);
            assertThrows(SQLException.class,()->table.updateStrict(primary("one"),Arrays.asList(
                new Column("Message",new DataValueString("new")),new Column("Missing",new DataValueString("value")))));
            try (Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery("SELECT Message FROM users")) {
                assertTrue(rows.next());assertEquals("old",rows.getString(1));
            }
            assertFalse(connection.isClosed());
        }
    }
    @Test void uncommittedOuterTransactionIsNotAcknowledged() throws Exception {
        try (Connection connection=database()) {
            UserTable table=fixture(connection);connection.setAutoCommit(false);
            assertThrows(SQLException.class,()->table.updateStrict(primary("new"),
                Collections.singletonList(new Column("Message",new DataValueString("new")))));
            connection.rollback();connection.setAutoCommit(true);
            try (Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery("SELECT COUNT(*) FROM users")) {
                assertTrue(rows.next());assertEquals(0,rows.getInt(1));
            }
        }
    }
    @Test void emptyWriteAndPrimaryMutationDoNotModifyData() throws Exception {
        try (Connection connection=database()) {
            UserTable table=fixture(connection);table.updateStrict(primary("one"),Collections.emptyList());
            assertThrows(IllegalArgumentException.class,()->table.updateStrict(primary("one"),Collections.singletonList(primary("other"))));
        }
    }
    @Test void nonPrimaryFilterCannotAcknowledgeAMultipleUserMutation() throws Exception {
        try (Connection connection=database()) {
            UserTable table=fixture(connection);
            assertThrows(IllegalArgumentException.class,()->table.updateStrict(
                new Column("Message",new DataValueString("same")),
                Collections.singletonList(new Column("Preserved",new DataValueString("new")))));
        }
    }
    @Test void unavailableConnectionProducesCheckedFailure() {
        UserTable table=fixture(null);
        SQLException failure=assertThrows(SQLException.class,()->table.updateStrict(primary("one"),
            Collections.singletonList(new Column("Message",new DataValueString("new")))));
        assertEquals("SQLite connection is unavailable",failure.getMessage());
        verify(table,never()).checkColumn(any());
    }
    @Test void checkedReadPreservesTypesAndMissingIdentityIsActuallyEmpty() throws Exception {
        try(Connection connection=database()) {
            try(Statement setup=connection.createStatement()) {
                setup.executeUpdate("ALTER TABLE users ADD Counter INTEGER");
                setup.executeUpdate("INSERT INTO users VALUES ('one','O''Brien','keep','true',7)");
            }
            UserTable table=fixture(connection);List<Column> row=table.getExactStrict(primary("one"));
            assertEquals(5,row.size());assertEquals("O'Brien",value(row,"Message").getString());
            assertEquals(7,value(row,"Counter").getInt());assertTrue(value(row,"Enabled").getBoolean());
            assertTrue(table.getExactStrict(primary("missing")).isEmpty());assertFalse(connection.isClosed());
        }
    }
    @Test void unavailableOrClosedReadCannotBecomeAnEmptySnapshot() throws Exception {
        assertThrows(SQLException.class,()->fixture(null).getExactStrict(primary("one")));
        Connection connection=database();UserTable table=fixture(connection);connection.close();
        assertThrows(SQLException.class,()->table.getExactStrict(primary("one")));
    }
    @Test void checkedReadRejectsOuterTransactionAndNonIdentityFilter() throws Exception {
        try(Connection connection=database()) {
            UserTable table=fixture(connection);connection.setAutoCommit(false);
            assertThrows(SQLException.class,()->table.getExactStrict(primary("one")));
            connection.rollback();connection.setAutoCommit(true);
            assertThrows(IllegalArgumentException.class,()->table.getExactStrict(new Column("Message",new DataValueString("one"))));
        }
    }
    private DataValue value(List<Column> values,String key) {
        return values.stream().filter(c->c.getName().equals(key)).findFirst().get().getValue();
    }
    private Connection database() throws Exception {
        Class.forName("org.sqlite.JDBC");Connection connection=DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement statement=connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE users (uuid TEXT PRIMARY KEY, Message TEXT, Preserved TEXT, Enabled TEXT)");
        }
        return connection;
    }
    private UserTable fixture(Connection connection) {
        SQLite sqlite=mock(SQLite.class);when(sqlite.getSQLConnection()).thenReturn(connection);
        AdvancedCorePlugin plugin=mock(AdvancedCorePlugin.class);
        com.bencodez.advancedcore.api.user.UserManager users=mock(com.bencodez.advancedcore.api.user.UserManager.class);
        com.bencodez.advancedcore.api.user.usercache.UserDataManager metadata=mock(com.bencodez.advancedcore.api.user.usercache.UserDataManager.class);
        when(plugin.getUserManager()).thenReturn(users);when(users.getDataManager()).thenReturn(metadata);
        when(metadata.isInt("Counter")).thenReturn(true);when(metadata.isBoolean("Enabled")).thenReturn(true);
        UserTable table=spy(new UserTable(plugin,"users",Collections.singletonList(primary("one"))));
        table.setSqLite(sqlite);
        // This fixture's schema is explicit; the test covers checked writes, not automatic ALTER.
        doNothing().when(table).checkColumn(any());return table;
    }
    private Column primary(String uuid) {return new Column("uuid",new DataValueString(uuid));}
}

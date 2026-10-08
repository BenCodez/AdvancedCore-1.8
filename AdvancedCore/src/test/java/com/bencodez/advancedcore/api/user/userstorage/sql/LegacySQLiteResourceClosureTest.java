package com.bencodez.advancedcore.api.user.userstorage.sql;

import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.simpleapi.sql.Column;
import com.bencodez.simpleapi.sql.data.DataValueString;
import com.bencodez.simpleapi.sql.sqlite.db.SQLite;

class LegacySQLiteResourceClosureTest {
    private static final String TABLE = "Users";

    private UserTable table(SQLite sqlite) {
        UserTable table = new UserTable(mock(AdvancedCorePlugin.class), TABLE,
                java.util.Collections.singletonList(new Column("uuid", new DataValueString(UUID.randomUUID().toString()))));
        table.setSqLite(sqlite);
        return table;
    }

    @Test
    void containsKeyClosesStatementAndResultSetOnEarlyMatch() throws Exception {
        SQLite sqlite = mock(SQLite.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(sqlite.getSQLConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT uuid FROM " + TABLE)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getString("uuid")).thenReturn("target");

        org.junit.jupiter.api.Assertions.assertTrue(table(sqlite).containsKey("target"));

        verify(result).close();
        verify(statement).close();
    }

    @Test
    void containsKeyClosesStatementWhenQueryThrows() throws Exception {
        SQLite sqlite = mock(SQLite.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(sqlite.getSQLConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT uuid FROM " + TABLE)).thenReturn(statement);
        when(statement.executeQuery()).thenThrow(new java.sql.SQLException("query failed"));

        org.junit.jupiter.api.Assertions.assertFalse(table(sqlite).containsKey("target"));

        verify(statement).close();
    }

    @Test
    void insertClosesStatementWhenUpdateThrows() throws Exception {
        SQLite sqlite = mock(SQLite.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(sqlite.getSQLConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        doThrow(new java.sql.SQLException("update failed")).when(statement).executeUpdate();

        UserTable table = spy(table(sqlite));
        doNothing().when(table).checkColumn(any(Column.class));
        table.insert(java.util.Collections.singletonList(
                new Column("uuid", new DataValueString(UUID.randomUUID().toString()))));

        verify(statement).close();
    }

    @Test
    void getRowsQueryFailurePreservesLegacyEmptyResultAndClosesStatement() throws Exception {
        SQLite sqlite = mock(SQLite.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(sqlite.getSQLConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT uuid FROM Users")).thenReturn(statement);
        when(statement.executeQuery()).thenThrow(new java.sql.SQLException("query unavailable"));
        org.junit.jupiter.api.Assertions.assertTrue(table(sqlite).getRows().isEmpty());
        verify(statement).close(); verify(connection,never()).close();
    }

    @Test
    void searchBindsBeforeExecutingAndClosesResourcesWithoutClosingConnection() throws Exception {
        SQLite sqlite = mock(SQLite.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(sqlite.getSQLConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT * FROM Users WHERE `name`=?")).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(false);

        UserTable table = new UserTable(mock(AdvancedCorePlugin.class), TABLE,
                java.util.Collections.singletonList(new Column("uuid", new DataValueString(UUID.randomUUID().toString()))));
        table.setSqLite(sqlite);
        table.search(new Column("name", new DataValueString("Alex")));

        InOrder order = inOrder(statement);
        order.verify(statement).setString(1, "Alex");
        order.verify(statement).executeQuery();
        verify(result).close();
        verify(statement).close();
        verify(connection, never()).close();
    }
}

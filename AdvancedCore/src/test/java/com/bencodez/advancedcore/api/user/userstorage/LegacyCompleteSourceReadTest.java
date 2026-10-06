package com.bencodez.advancedcore.api.user.userstorage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.user.UserManager;
import com.bencodez.advancedcore.api.user.usercache.UserDataManager;
import com.bencodez.advancedcore.api.user.userstorage.mysql.MySQL;
import com.bencodez.advancedcore.api.user.userstorage.sql.UserTable;
import com.bencodez.simpleapi.sql.*;

class LegacyCompleteSourceReadTest {
    final String identity="8d17c3ab-1085-4e22-95ad-8c975f3e4c24";
    @Test void sqlitePreparationFailureCannotReturnAnEmptySuccessfulSource() throws Exception {
        UserTable table=new UserTable(plugin(),"Users",java.util.Collections.singletonList(new Column("uuid",DataType.STRING)));
        com.bencodez.simpleapi.sql.sqlite.db.SQLite sqlite=mock(com.bencodez.simpleapi.sql.sqlite.db.SQLite.class);
        Connection source=mock(Connection.class);when(source.getAutoCommit()).thenReturn(true);when(source.prepareStatement(anyString())).thenThrow(new SQLException("fixture unavailable"));when(sqlite.getSQLConnection()).thenReturn(source);field(table,"sqLite",sqlite);
        assertThrows(SQLException.class,table::getAllQueryStrict);
    }
    @Test void sqliteIterationFailureCannotReturnItsAlreadyReadPrefix() throws Exception {
        UserTable table=new UserTable(plugin(),"Users",java.util.Collections.singletonList(new Column("uuid",DataType.STRING)));
        com.bencodez.simpleapi.sql.sqlite.db.SQLite sqlite=mock(com.bencodez.simpleapi.sql.sqlite.db.SQLite.class);
        Connection source=rows();when(sqlite.getSQLConnection()).thenReturn(source);field(table,"sqLite",sqlite);
        assertThrows(SQLException.class,table::getAllQueryStrict);
    }
    AdvancedCorePlugin plugin() {
        AdvancedCorePlugin plugin=mock(AdvancedCorePlugin.class);UserManager users=mock(UserManager.class);UserDataManager data=mock(UserDataManager.class);
        when(plugin.getUserManager()).thenReturn(users);when(users.getDataManager()).thenReturn(data);return plugin;
    }
    Connection rows() throws Exception {
        Connection connection=mock(Connection.class);PreparedStatement statement=mock(PreparedStatement.class);ResultSet rows=mock(ResultSet.class);ResultSetMetaData metadata=mock(ResultSetMetaData.class);
        when(connection.getAutoCommit()).thenReturn(true);when(connection.prepareStatement(anyString())).thenReturn(statement);when(statement.executeQuery()).thenReturn(rows);
        when(rows.getMetaData()).thenReturn(metadata);when(metadata.getColumnCount()).thenReturn(1);when(metadata.getColumnLabel(1)).thenReturn("uuid");when(rows.getString(1)).thenReturn(identity);
        when(rows.next()).thenReturn(true).thenThrow(new SQLException("fixture stream failed"));return connection;
    }
    static void field(Object instance,String name,Object value)throws Exception {java.lang.reflect.Field field=instance.getClass().getDeclaredField(name);if(instance instanceof MySQL)field=MySQL.class.getDeclaredField(name);field.setAccessible(true);field.set(instance,value);}
}

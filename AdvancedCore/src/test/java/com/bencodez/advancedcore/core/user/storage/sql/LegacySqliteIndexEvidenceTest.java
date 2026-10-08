package com.bencodez.advancedcore.core.user.storage.sql;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacySqliteIndexEvidenceTest {
    @TempDir Path directory;

    @Test void onlyKnownPrePartialIndexVersionsCanReplaceAbsentMetadata() {
        for (String version : new String[] {"3.0.0", "3.7.2", "3.7.17"}) {
            assertTrue(SqliteUserBackend.predatesPartialIndexes(version));
        }
        for (String version : new String[] {"3.8.0", "3.42.0", "4.0.0", "2.8.0", "3.7", "3.7.2-custom", " 3.7.2", "", null}) {
            assertFalse(SqliteUserBackend.predatesPartialIndexes(version));
        }
    }

    private boolean inspect(Connection connection, ResultSet index) throws Throwable {
        try (SqliteUserBackend backend = new SqliteUserBackend(directory, "Users", "Users", SqlUserSchema.builder().build(), SqlBackendLogger.NO_OP)) {
            Method method = SqliteUserBackend.class.getDeclaredMethod("hasPartialIndex", Connection.class, ResultSet.class);
            method.setAccessible(true);
            try { return (Boolean) method.invoke(backend, connection, index); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
    }

    @Test void absentMetadataRequiresEngineEvidenceAndDoesNotHideDatabaseFailure() throws Throwable {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet version = mock(ResultSet.class), index = mock(ResultSet.class);
        when(index.getInt("partial")).thenThrow(new SQLException("no partial column"));
        when(connection.prepareStatement("SELECT sqlite_version()")).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(version);
        when(version.next()).thenReturn(true);
        when(version.getString(1)).thenReturn("3.7.2");
        assertFalse(inspect(connection, index));
        when(version.getString(1)).thenReturn("3.42.0"); assertTrue(inspect(connection, index));
        when(version.getString(1)).thenReturn(null); assertTrue(inspect(connection, index));
        when(version.next()).thenReturn(false); assertTrue(inspect(connection, index));
        SQLException disconnected = new SQLException("engine query failed");
        when(statement.executeQuery()).thenThrow(disconnected);
        assertSame(disconnected, assertThrows(SQLException.class, () -> inspect(connection, index)));
        verify(statement, times(5)).close(); verify(version, times(4)).close();
    }

    @Test void reportedPartialIndexDoesNotConsultVersionOrBecomeUniqueEvidence() throws Throwable {
        Connection connection = mock(Connection.class); ResultSet index = mock(ResultSet.class);
        when(index.getInt("partial")).thenReturn(1); assertTrue(inspect(connection, index));
        when(index.getInt("partial")).thenReturn(0); assertFalse(inspect(connection, index));
        when(index.wasNull()).thenReturn(true); assertTrue(inspect(connection, index));
        verifyNoInteractions(connection);
    }
}

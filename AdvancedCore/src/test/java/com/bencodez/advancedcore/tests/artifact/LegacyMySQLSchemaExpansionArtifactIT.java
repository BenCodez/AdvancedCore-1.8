package com.bencodez.advancedcore.tests.artifact;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.InvocationTargetException;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.user.usercache.keys.*;
import com.bencodez.simpleapi.sql.DataType;

class LegacyMySQLSchemaExpansionArtifactIT {
    @Test void registeredIntegerExpansionPreservesDeclaredDefault() throws Exception {
        try(Fixture f=new Fixture(new UserDataKeyInt("Points"))) {
            f.add("Points",DataType.INTEGER);
            verify(f.connection).prepareStatement("ALTER TABLE users ADD COLUMN `Points` INT DEFAULT '0';");
            verify(f.statement).executeUpdate();assertTrue(f.columns().contains("Points"));
            verify(f.connection).close();verify(f.statement).close();
        }
    }
    @Test void registeredStringExpansionPreservesLengthAndDeclaredAttributes() throws Exception {
        try(Fixture f=new Fixture(new UserDataKeyString("PlayerName").setColumnType("VARCHAR(30) NOT NULL DEFAULT ''"))) {
            f.add("PlayerName",DataType.STRING);
            verify(f.connection).prepareStatement("ALTER TABLE users ADD COLUMN `PlayerName` VARCHAR(30) NOT NULL DEFAULT '';");
            verify(f.statement).executeUpdate();
        }
    }
    @Test void failedExpansionIsVisibleAndDoesNotPublishColumnMembership() throws Exception {
        try(Fixture f=new Fixture(new UserDataKeyInt("Points"))) {
            SQLException denied=new SQLException("DDL denied");doThrow(denied).when(f.statement).executeUpdate();
            InvocationTargetException result=assertThrows(InvocationTargetException.class,()->f.add("Points",DataType.INTEGER));
            assertTrue(result.getCause() instanceof IllegalStateException);assertSame(denied,result.getCause().getCause());
            assertFalse(f.columns().contains("Points"));verify(f.connection).close();verify(f.statement).close();
        }
    }
    @Test void unknownDynamicColumnRetainsLegacyTextStorage() throws Exception {
        try(Fixture f=new Fixture(new UserDataKeyInt("Points"))) {
            f.add("Dynamic",DataType.INTEGER);verify(f.connection).prepareStatement("ALTER TABLE users ADD COLUMN `Dynamic` text;");
            verify(f.statement).executeUpdate();
        }
    }
    @Test void checkedExpansionCannotCommitAnExistingBorrowedTransaction() throws Exception {
        try(Fixture f=new Fixture(new UserDataKeyInt("Points"))) {
            when(f.connection.getAutoCommit()).thenReturn(false);
            InvocationTargetException result=assertThrows(InvocationTargetException.class,()->f.add("Points",DataType.INTEGER));
            assertTrue(result.getCause() instanceof IllegalStateException);assertTrue(result.getCause().getCause() instanceof SQLException);
            verify(f.connection,never()).prepareStatement(anyString());assertFalse(f.columns().contains("Points"));verify(f.connection).close();
        }
    }
    @Test void existingCaseFoldedColumnIsRememberedWithoutDuplicateDdl() throws Exception {
        try(Fixture f=new Fixture(new UserDataKeyInt("Points"))) {
            when(f.metadata.getColumnCount()).thenReturn(2);when(f.metadata.getColumnName(2)).thenReturn("points");
            f.add("Points",DataType.INTEGER);f.add("Points",DataType.INTEGER);
            verify(f.statement,never()).executeUpdate();assertEquals(1,Collections.frequency(f.columns(),"Points"));
            verify(f.schemaRows,times(2)).close();
        }
    }
    @Test void missingSchemaEvidenceCannotBeTreatedAsMissingColumn() throws Exception {
        try(Fixture f=new Fixture(new UserDataKeyInt("Points"))) {
            SQLException unavailable=new SQLException("schema inspection unavailable");when(f.schemaQuery.executeQuery()).thenThrow(unavailable);
            InvocationTargetException result=assertThrows(InvocationTargetException.class,()->f.add("Points",DataType.INTEGER));
            assertSame(unavailable,result.getCause().getCause());verify(f.statement,never()).executeUpdate();assertFalse(f.columns().contains("Points"));
        }
    }
    @Test void duplicateColumnRaceSucceedsOnlyAfterLiveSchemaReinspection() throws Exception {
        try(Fixture f=new Fixture(new UserDataKeyInt("Points"))) {
            when(f.metadata.getColumnCount()).thenReturn(1,2);when(f.metadata.getColumnName(2)).thenReturn("Points");
            doThrow(new SQLException("peer created column","42S21",1060)).when(f.statement).executeUpdate();
            f.add("Points",DataType.INTEGER);assertEquals(1,Collections.frequency(f.columns(),"Points"));
            verify(f.schemaQuery,times(2)).executeQuery();
        }
    }
    @Test void duplicateErrorWithoutActualColumnRemainsFailure() throws Exception {
        try(Fixture f=new Fixture(new UserDataKeyInt("Points"))) {
            SQLException duplicate=new SQLException("duplicate but no live column","42S21",1060);doThrow(duplicate).when(f.statement).executeUpdate();
            InvocationTargetException result=assertThrows(InvocationTargetException.class,()->f.add("Points",DataType.INTEGER));
            assertSame(duplicate,result.getCause().getCause());assertFalse(f.columns().contains("Points"));verify(f.schemaQuery,times(2)).executeQuery();
        }
    }
    @Test void cachedColumnLookupSupportsPublicListImplementations() throws Exception {
        try(Fixture f=new Fixture(new UserDataKeyInt("Points"))) {
            f.set("columns",Collections.synchronizedList(new ArrayList<>(Arrays.asList("uuid","points"))));
            f.check("Points",DataType.INTEGER);verifyNoInteractions(f.schemaQuery);verify(f.statement,never()).executeUpdate();
        }
    }
    @Test void wrongDuplicateSqlStateIsNotAcceptedAsPeerCompletion() throws Exception {
        try(Fixture f=new Fixture(new UserDataKeyInt("Points"))) {
            SQLException unrelated=new SQLException("wrong state","42000",1060);doThrow(unrelated).when(f.statement).executeUpdate();
            InvocationTargetException result=assertThrows(InvocationTargetException.class,()->f.add("Points",DataType.INTEGER));
            assertSame(unrelated,result.getCause().getCause());verify(f.schemaQuery,times(1)).executeQuery();assertFalse(f.columns().contains("Points"));
        }
    }
    @Test void peerDuplicateWithCleanupFailureIsNotAcknowledged() throws Exception {
        try(Fixture f=new Fixture(new UserDataKeyInt("Points"))) {
            SQLException duplicate=new SQLException("peer duplicate","42S21",1060),cleanup=new SQLException("statement cleanup failed");
            doThrow(duplicate).when(f.statement).executeUpdate();doThrow(cleanup).when(f.statement).close();
            InvocationTargetException result=assertThrows(InvocationTargetException.class,()->f.add("Points",DataType.INTEGER));
            assertSame(duplicate,result.getCause().getCause());assertArrayEquals(new Throwable[]{cleanup},duplicate.getSuppressed());
            verify(f.schemaQuery,times(1)).executeQuery();assertFalse(f.columns().contains("Points"));
        }
    }
    @Test void absentMetadataIsFailureWithoutDdl() throws Exception {
        try(Fixture f=new Fixture(new UserDataKeyInt("Points"))) {
            when(f.schemaRows.getMetaData()).thenReturn(null);
            InvocationTargetException result=assertThrows(InvocationTargetException.class,()->f.add("Points",DataType.INTEGER));
            assertTrue(result.getCause().getCause() instanceof SQLException);verify(f.statement,never()).executeUpdate();assertFalse(f.columns().contains("Points"));
        }
    }
    @Test void committedDdlWithFailedCleanupCanRetryWithoutRepeatingDdl() throws Exception {
        try(Fixture f=new Fixture(new UserDataKeyInt("Points"))) {
            SQLException cleanup=new SQLException("connection cleanup failed");doThrow(cleanup).when(f.connection).close();
            when(f.metadata.getColumnCount()).thenReturn(1,2);when(f.metadata.getColumnName(2)).thenReturn("Points");
            InvocationTargetException result=assertThrows(InvocationTargetException.class,()->f.add("Points",DataType.INTEGER));
            assertSame(cleanup,result.getCause().getCause());assertFalse(f.columns().contains("Points"));
            doNothing().when(f.connection).close();f.add("Points",DataType.INTEGER);
            verify(f.statement,times(1)).executeUpdate();assertEquals(1,Collections.frequency(f.columns(),"Points"));
        }
    }
    @Test void failedPeerReinspectionKeepsOriginalDdlFailureAndInspectionEvidence() throws Exception {
        try(Fixture f=new Fixture(new UserDataKeyInt("Points"))) {
            SQLException duplicate=new SQLException("peer duplicate","42S21",1060),unavailable=new SQLException("reinspection unavailable");
            doThrow(duplicate).when(f.statement).executeUpdate();when(f.schemaQuery.executeQuery()).thenReturn(f.schemaRows).thenThrow(unavailable);
            InvocationTargetException result=assertThrows(InvocationTargetException.class,()->f.add("Points",DataType.INTEGER));
            assertSame(duplicate,result.getCause().getCause());assertArrayEquals(new Throwable[]{unavailable},duplicate.getSuppressed());assertFalse(f.columns().contains("Points"));
        }
    }
    @Test void existingColumnWithFailedInspectionCleanupCannotPublishMembership() throws Exception {
        try(Fixture f=new Fixture(new UserDataKeyInt("Points"))) {
            when(f.metadata.getColumnCount()).thenReturn(2);when(f.metadata.getColumnName(2)).thenReturn("Points");
            SQLException cleanup=new SQLException("inspection cleanup failed");doThrow(cleanup).when(f.schemaRows).close();
            InvocationTargetException result=assertThrows(InvocationTargetException.class,()->f.add("Points",DataType.INTEGER));
            assertSame(cleanup,result.getCause().getCause());verify(f.statement,never()).executeUpdate();assertFalse(f.columns().contains("Points"));
        }
    }
    static class Fixture extends LegacyMySQLCheckedWriteArtifactIT.Fixture {
        final PreparedStatement schemaQuery=mock(PreparedStatement.class);
        final ResultSet schemaRows=mock(ResultSet.class);final ResultSetMetaData metadata=mock(ResultSetMetaData.class);
        Fixture(UserDataKey key) throws Exception {
            set("object3",new Object());set("columns",new ArrayList<>(Collections.singletonList("uuid")));
            AdvancedCorePlugin plugin=mock(AdvancedCorePlugin.class,RETURNS_DEEP_STUBS);set("plugin",plugin);
            when(plugin.getUserManager().getDataManager().getRegisteredKeysSnapshot()).thenReturn(new ArrayList<>(Collections.singletonList(key)));
            when(connection.prepareStatement(startsWith("SELECT * FROM "))).thenReturn(schemaQuery);
            when(schemaQuery.executeQuery()).thenReturn(schemaRows);when(schemaRows.getMetaData()).thenReturn(metadata);
            when(metadata.getColumnCount()).thenReturn(1);when(metadata.getColumnName(1)).thenReturn("uuid");
        }
        void add(String column,DataType type) throws Exception {storeType.getMethod("addColumn",String.class,DataType.class).invoke(store,column,type);}
        void check(String column,DataType type) throws Exception {storeType.getMethod("checkColumn",String.class,DataType.class).invoke(store,column,type);}
        @SuppressWarnings("unchecked") List<String> columns() throws Exception {return (List<String>)storeType.getMethod("getColumns").invoke(store);}
    }
}

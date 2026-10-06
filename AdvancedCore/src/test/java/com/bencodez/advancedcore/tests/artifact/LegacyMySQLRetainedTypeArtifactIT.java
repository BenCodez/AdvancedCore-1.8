package com.bencodez.advancedcore.tests.artifact;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.InvocationTargetException;
import java.sql.*;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.api.user.usercache.keys.UserDataKeyString;
import com.bencodez.simpleapi.sql.DataType;

class LegacyMySQLRetainedTypeArtifactIT {
    @Test void retainedIntegerToTextPreservesPhysicalAttributes() throws Exception {
        try(Fixture f=new Fixture("TEXT")) {
            f.add("RetainedValue",DataType.STRING);
            verify(f.statement).executeUpdate();
            verify(f.connection).prepareStatement("ALTER TABLE `users` MODIFY COLUMN `RetainedValue` TEXT NOT NULL DEFAULT '7' COMMENT 'legacy value';");
            assertTrue(f.columns().contains("RetainedValue"));verify(f.connection).close();
        }
    }
    @Test void cachedMembershipStillReconcilesBeforeWriteAndThenSkipsRepeatedSchemaWork() throws Exception {
        try(Fixture f=new Fixture("TEXT")) {
            f.set("columns",new java.util.ArrayList<>(java.util.Arrays.asList("uuid","RetainedValue")));
            f.check("RetainedValue",DataType.STRING);f.check("RetainedValue",DataType.STRING);
            verify(f.statement,times(1)).executeUpdate();verify(f.attributesQuery,times(1)).executeQuery();
            verify(f.schemaQuery,times(3)).executeQuery();
        }
    }
    @Test void deliberatelyNumericStringDeclarationIsNotBlindlyConverted() throws Exception {
        try(Fixture f=new Fixture("BIGINT")) {
            f.add("RetainedValue",DataType.STRING);verify(f.statement,never()).executeUpdate();
            verify(f.attributesQuery,never()).executeQuery();
        }
    }
    @Test void legacyBooleanStringKeyKeepsItsBooleanStorageContract() throws Exception {
        try(Fixture f=new Fixture("VARCHAR(5)")) {
            com.bencodez.advancedcore.AdvancedCorePlugin plugin=mock(com.bencodez.advancedcore.AdvancedCorePlugin.class,RETURNS_DEEP_STUBS);
            when(plugin.getUserManager().getDataManager().getRegisteredKeysSnapshot()).thenReturn(new java.util.ArrayList<>(java.util.Collections.singletonList(new UserDataKeyString("RetainedValue").setColumnType("VARCHAR(5)"))));
            when(plugin.getUserManager().getDataManager().isBoolean("RetainedValue")).thenReturn(true);f.set("plugin",plugin);
            f.add("RetainedValue",DataType.BOOLEAN);verify(f.statement,never()).executeUpdate();verifyNoInteractions(f.attributesQuery);
        }
    }
    @Test void generatedColumnAndUnsafeDefaultCannotBeReconciledAsOrdinaryText() throws Exception {
        for(String attribute:new String[]{"extra","default","comment"}) {
            try(Fixture f=new Fixture("TEXT")) {
                when(f.attributes.getString(attribute.equals("extra")?3:attribute.equals("default")?2:4))
                    .thenReturn(attribute.equals("extra")?"auto_increment":attribute.equals("default")?"CURRENT_TIMESTAMP":"back\\slash");
                assertThrows(InvocationTargetException.class,()->f.add("RetainedValue",DataType.STRING));
                verify(f.statement,never()).executeUpdate();assertFalse(f.columns().contains("RetainedValue"));
            }
        }
    }
    @Test void peerCompletingBetweenTypeObservationsSkipsDuplicateMigration() throws Exception {
        try(Fixture f=new Fixture("TEXT")) {
            when(f.metadata.getColumnType(2)).thenReturn(Types.INTEGER,Types.VARCHAR);
            when(f.attributes.next()).thenReturn(true,false,true,false);
            when(f.attributes.getString(5)).thenReturn("int(11)","text");
            f.add("RetainedValue",DataType.STRING);
            verify(f.statement,never()).executeUpdate();assertTrue(f.columns().contains("RetainedValue"));
        }
    }
    @Test void failedDdlRequiresExactPeerTypeAndPreservedAttributes() throws Exception {
        for(boolean compatible:new boolean[]{true,false}) {
            try(Fixture f=new Fixture("TEXT")) {
                doThrow(new SQLException("fixture competing alteration")).when(f.statement).executeUpdate();
                when(f.attributes.next()).thenReturn(true,false,true,false);
                when(f.attributes.getString(5)).thenReturn("int(11)",compatible?"text":"int(11)");
                if(compatible)f.add("RetainedValue",DataType.STRING);
                else assertThrows(InvocationTargetException.class,()->f.add("RetainedValue",DataType.STRING));
                assertEquals(compatible,f.columns().contains("RetainedValue"));verify(f.attributesQuery,times(2)).executeQuery();
            }
        }
    }
    @Test void missingAttributeEvidenceFailsBeforeDdl() throws Exception {
        try(Fixture f=new Fixture("TEXT")) {
            when(f.attributes.getString(1)).thenReturn(null);
            assertThrows(InvocationTargetException.class,()->f.add("RetainedValue",DataType.STRING));
            verify(f.statement,never()).executeUpdate();assertFalse(f.columns().contains("RetainedValue"));
        }
    }
    @Test void unavailableTypeIsNotAlreadyCompatibleEvidence() throws Exception {
        try(Fixture f=new Fixture("TEXT")) {
            when(f.metadata.getColumnType(2)).thenReturn(0);
            assertThrows(InvocationTargetException.class,()->f.add("RetainedValue",DataType.STRING));
            verify(f.statement,never()).executeUpdate();assertFalse(f.columns().contains("RetainedValue"));
        }
    }
    @Test void successfulDdlWithCloseFailureCannotPublishMembership() throws Exception {
        try(Fixture f=new Fixture("TEXT")) {
            doThrow(new SQLException("fixture cleanup failed")).when(f.statement).close();
            assertThrows(InvocationTargetException.class,()->f.add("RetainedValue",DataType.STRING));
            verify(f.statement).executeUpdate();assertFalse(f.columns().contains("RetainedValue"));
            verify(f.attributesQuery,times(1)).executeQuery();
        }
    }
    @Test void strictModeIsRestoredAfterMigration() throws Exception {
        try(Fixture f=new Fixture("TEXT")) {
            when(f.modeRows.getString(1)).thenReturn("NO_ENGINE_SUBSTITUTION");f.add("RetainedValue",DataType.STRING);
            org.mockito.InOrder order=inOrder(f.modeSet,f.statement);
            order.verify(f.modeSet).setString(1,"NO_ENGINE_SUBSTITUTION,STRICT_ALL_TABLES");order.verify(f.modeSet).executeUpdate();
            order.verify(f.statement).executeUpdate();order.verify(f.modeSet).setString(1,"NO_ENGINE_SUBSTITUTION");order.verify(f.modeSet).executeUpdate();
        }
    }
    @Test void unavailableModePreventsMigration() throws Exception {
        try(Fixture f=new Fixture("TEXT")) {
            when(f.modeRows.getString(1)).thenReturn(null);
            assertThrows(InvocationTargetException.class,()->f.add("RetainedValue",DataType.STRING));
            verify(f.statement,never()).executeUpdate();verify(f.modeSet,never()).executeUpdate();
        }
    }
    @Test void restoreFailureDoesNotPublishSuccessfulMigration() throws Exception {
        try(Fixture f=new Fixture("TEXT")) {
            Object pool=mock(f.manager.getClass().getMethod("getDataSource").getReturnType());
            when(f.manager.getClass().getMethod("getDataSource").invoke(f.manager)).thenReturn(pool);
            when(f.modeSet.executeUpdate()).thenReturn(0).thenThrow(new SQLException("fixture restore unavailable"));
            assertThrows(InvocationTargetException.class,()->f.add("RetainedValue",DataType.STRING));
            verify(f.statement).executeUpdate();assertFalse(f.columns().contains("RetainedValue"));pool.getClass().getMethod("evictConnection",Connection.class).invoke(verify(pool),f.connection);
        }
    }
    static final class Fixture extends LegacyMySQLSchemaExpansionArtifactIT.Fixture {
        final PreparedStatement attributesQuery=mock(PreparedStatement.class);
        final ResultSet attributes=mock(ResultSet.class);
        final PreparedStatement modeQuery=mock(PreparedStatement.class),modeSet=mock(PreparedStatement.class);
        final ResultSet modeRows=mock(ResultSet.class);
        Fixture(String declaration) throws Exception {
            super(new UserDataKeyString("RetainedValue").setColumnType(declaration));
            when(connection.prepareStatement("SELECT @@SESSION.sql_mode")).thenReturn(modeQuery);
            when(connection.prepareStatement("SET SESSION sql_mode=?")).thenReturn(modeSet);
            when(modeQuery.executeQuery()).thenReturn(modeRows);when(modeRows.next()).thenReturn(true,false);when(modeRows.getString(1)).thenReturn("");
            when(metadata.getColumnCount()).thenReturn(2);when(metadata.getColumnName(2)).thenReturn("RetainedValue");
            when(metadata.getColumnType(2)).thenReturn(Types.INTEGER);
            when(connection.prepareStatement(startsWith("SELECT IS_NULLABLE"))).thenReturn(attributesQuery);
            when(attributesQuery.executeQuery()).thenReturn(attributes);when(attributes.next()).thenReturn(true,false);
            when(attributes.getString(1)).thenReturn("NO");when(attributes.getString(2)).thenReturn("7");
            when(attributes.getString(3)).thenReturn("");when(attributes.getString(4)).thenReturn("legacy value");when(attributes.getString(5)).thenReturn("int(11)");
        }
    }
}

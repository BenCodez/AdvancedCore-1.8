package com.bencodez.advancedcore.tests.artifact;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.InvocationTargetException;
import java.sql.*;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.api.user.usercache.keys.UserDataKeyString;
import com.bencodez.advancedcore.api.user.usercache.keys.UserDataKey;
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
            when(f.metadata.getColumnType(2)).thenReturn(Types.BIGINT);when(f.attributes.getString(5)).thenReturn("bigint(20)");
            f.add("RetainedValue",DataType.STRING);verify(f.statement,never()).executeUpdate();
            verify(f.attributesQuery,times(1)).executeQuery();
        }
    }
    @Test void narrowerRetainedIntegerReconcilesToRegisteredBigintRatherThanText() throws Exception {
        try(Fixture f=new Fixture("BIGINT")) {
            f.add("RetainedValue",DataType.STRING);
            verify(f.connection).prepareStatement("ALTER TABLE `users` MODIFY COLUMN `RetainedValue` BIGINT NOT NULL DEFAULT '7' COMMENT 'legacy value';");
            verify(f.statement).executeUpdate();assertTrue(f.columns().contains("RetainedValue"));
        }
    }
    @Test void peerCompletingNumericMigrationSkipsSecondAlter() throws Exception {
        try(Fixture f=new Fixture("BIGINT")) {
            when(f.attributes.getString(5)).thenReturn("int(11)","bigint(20)");
            f.add("RetainedValue",DataType.STRING);verify(f.statement,never()).executeUpdate();
            assertTrue(f.columns().contains("RetainedValue"));verify(f.attributesQuery,times(2)).executeQuery();
        }
    }
    @Test void fractionalScaleReductionFailsBeforeDdlWithoutPublishingSchema() throws Exception {
        try(Fixture f=new Fixture("DECIMAL(12,2)")) {
            f.decimalFence();when(f.valueRows.next()).thenReturn(true);
            InvocationTargetException result=assertThrows(InvocationTargetException.class,()->f.add("RetainedValue",DataType.STRING));
            assertEquals("Retained decimal values would be rounded",result.getCause().getCause().getMessage());
            verify(f.statement,never()).executeUpdate();verify(f.modeSet,never()).executeUpdate();assertFalse(f.columns().contains("RetainedValue"));
            verify(f.unlock).executeUpdate();verify(f.connection).setAutoCommit(true);f.verifyEviction();
        }
    }
    @Test void exactDecimalScaleReductionOwnsValidationThroughDdlAndDiscardsModifiedConnection() throws Exception {
        try(Fixture f=new Fixture("DECIMAL(12,2)")) {
            f.decimalFence();f.add("RetainedValue",DataType.STRING);
            org.mockito.InOrder order=inOrder(f.connection,f.lock,f.valueQuery,f.defaultQuery,f.statement,f.unlock);
            order.verify(f.connection).setAutoCommit(false);order.verify(f.lock).executeUpdate();
            order.verify(f.valueQuery).executeQuery();order.verify(f.defaultQuery).executeQuery();
            order.verify(f.statement).executeUpdate();order.verify(f.connection).rollback();
            order.verify(f.unlock).executeUpdate();order.verify(f.connection).setAutoCommit(true);
            assertTrue(f.columns().contains("RetainedValue"));f.verifyEviction();
        }
    }
    @Test void jdkConnectionWrapperDoesNotHideSupportedDriver() throws Exception {
        try(Fixture f=new Fixture("DECIMAL(12,2)")) {
            f.decimalFence();
            Connection wrapper=(Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
                try{return method.invoke(f.connection,args);}catch(InvocationTargetException failure){throw failure.getCause();}
            });
            assertSame(Connection.class.getClassLoader(), wrapper.getClass().getClassLoader());
            f.borrowed.set(wrapper);f.add("RetainedValue",DataType.STRING);
            verify(f.statement).executeUpdate();assertTrue(f.columns().contains("RetainedValue"));
            f.pool.getClass().getMethod("evictConnection",Connection.class).invoke(verify(f.pool),wrapper);
        }
    }
    @Test void fencedDecimalDdlFailureCannotBeReinterpretedAsPeerSuccess() throws Exception {
        try(Fixture f=new Fixture("DECIMAL(12,2)")) {
            f.decimalFence();when(f.attributes.getString(5)).thenReturn("decimal(12,3)","decimal(12,3)","decimal(12,3)","decimal(12,2)");
            when(f.statement.executeUpdate()).thenThrow(new SQLException("fixture lost DDL ownership"));
            assertThrows(InvocationTargetException.class,()->f.add("RetainedValue",DataType.STRING));
            assertFalse(f.columns().contains("RetainedValue"));f.verifyEviction();
            verify(f.attributesQuery,times(3)).executeQuery();
        }
    }
    @Test void uncheckedCleanupFailureStillEvictsAndCannotPublishMembership() throws Exception {
        try(Fixture f=new Fixture("DECIMAL(12,2)")) {
            f.decimalFence();doThrow(new IllegalStateException("fixture rollback fault")).when(f.connection).rollback();
            assertThrows(InvocationTargetException.class,()->f.add("RetainedValue",DataType.STRING));
            verify(f.statement).executeUpdate();verify(f.unlock).executeUpdate();verify(f.connection).setAutoCommit(true);
            assertFalse(f.columns().contains("RetainedValue"));f.verifyEviction();
        }
    }
    @Test void initiallyEnabledReconnectCannotLoseDecimalOwnershipSilently() throws Exception {
        try(Fixture f=new Fixture("DECIMAL(12,2)")) {
            f.decimalFence();f.driverProperties.setProperty("autoReconnect","true");
            InvocationTargetException result=assertThrows(InvocationTargetException.class,()->f.add("RetainedValue",DataType.STRING));
            assertEquals("Decimal migration requires automatic reconnect disabled at connection creation",result.getCause().getCause().getMessage());
            verify(f.lock,never()).executeUpdate();verify(f.statement,never()).executeUpdate();assertFalse(f.columns().contains("RetainedValue"));
            f.pool.getClass().getMethod("evictConnection",Connection.class).invoke(verify(f.pool,never()),f.connection);
        }
    }
    @Test void decimalDefaultAndMissingValueEvidenceCannotAuthorizeDdl() throws Exception {
        for(boolean missing:new boolean[]{false,true})try(Fixture f=new Fixture("DECIMAL(12,2)")) {
            f.decimalFence();
            if(missing)when(f.valueQuery.executeQuery()).thenReturn(null);
            else when(f.defaultRows.getBigDecimal(1)).thenReturn(new java.math.BigDecimal("6.99"));
            assertThrows(InvocationTargetException.class,()->f.add("RetainedValue",DataType.STRING));
            verify(f.statement,never()).executeUpdate();assertFalse(f.columns().contains("RetainedValue"));
            verify(f.unlock).executeUpdate();f.verifyEviction();
        }
    }
    @Test void decimalLockAndUnlockFailuresNeverPublishMembership() throws Exception {
        for(boolean acquire:new boolean[]{true,false})try(Fixture f=new Fixture("DECIMAL(12,2)")) {
            f.decimalFence();when((acquire?f.lock:f.unlock).executeUpdate()).thenThrow(new SQLException("fixture lock failure"));
            assertThrows(InvocationTargetException.class,()->f.add("RetainedValue",DataType.STRING));
            if(acquire)verify(f.statement,never()).executeUpdate();else verify(f.statement).executeUpdate();
            assertFalse(f.columns().contains("RetainedValue"));f.verifyEviction();
        }
    }
    @Test void decimalGaleraAndUnavailableLockEvidenceAreRejectedBeforeLock() throws Exception {
        for(boolean galera:new boolean[]{true,false})try(Fixture f=new Fixture("DECIMAL(12,2)")) {
            f.decimalFence();
            if(galera){when(f.serverRows.next()).thenReturn(true,true,false);when(f.serverRows.getString(1)).thenReturn("innodb_table_locks","wsrep_on");when(f.serverRows.getString(2)).thenReturn("ON");}
            else when(f.serverRows.next()).thenReturn(false);
            assertThrows(InvocationTargetException.class,()->f.add("RetainedValue",DataType.STRING));
            verify(f.lock,never()).executeUpdate();verify(f.statement,never()).executeUpdate();assertFalse(f.columns().contains("RetainedValue"));f.verifyEviction();
        }
    }
    @Test void peerDecimalWideningKeepsEquivalentDefaultButRejectsChangedValue() throws Exception {
        for(boolean equivalent:new boolean[]{true,false}) {
            try(Fixture f=new Fixture("DECIMAL(14,4)")) {
                when(f.metadata.getColumnType(2)).thenReturn(Types.DECIMAL);
                when(f.attributes.getString(5)).thenReturn("decimal(12,3)","decimal(14,4)");
                when(f.attributes.getString(2)).thenReturn("7.000",equivalent?"7.0000":"8.0000");
                if(equivalent)f.add("RetainedValue",DataType.STRING);
                else assertThrows(InvocationTargetException.class,()->f.add("RetainedValue",DataType.STRING));
                verify(f.statement,never()).executeUpdate();assertEquals(equivalent,f.columns().contains("RetainedValue"));
            }
        }
    }
    @Test void extensionDeclarationIsResolvedOnceOutsideSchemaMonitors() throws Exception {
        for (boolean directAdd : new boolean[]{false,true}) {
            java.util.concurrent.atomic.AtomicReference<Object> checkLock=new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<Object> addLock=new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicInteger callbacks=new java.util.concurrent.atomic.AtomicInteger();
            UserDataKey key=new UserDataKeyString("RetainedValue") {
                @Override public String getColumnType() {
                    callbacks.incrementAndGet();
                    assertFalse(Thread.holdsLock(checkLock.get()),"Extension callback must not run under schema check monitor");
                    assertFalse(Thread.holdsLock(addLock.get()),"Extension callback must not run under schema add monitor");
                    return "TEXT";
                }
            };
            try(Fixture f=new Fixture(key)) {
                java.lang.reflect.Field check=f.storeType.getDeclaredField("object4");check.setAccessible(true);checkLock.set(check.get(f.store));
                java.lang.reflect.Field add=f.storeType.getDeclaredField("object3");add.setAccessible(true);addLock.set(add.get(f.store));
                if(directAdd)f.add("RetainedValue",DataType.STRING);else f.check("RetainedValue",DataType.STRING);
                assertEquals(1,callbacks.get());verify(f.statement).executeUpdate();
            }
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
        final PreparedStatement lock=mock(PreparedStatement.class),unlock=mock(PreparedStatement.class),
            serverQuery=mock(PreparedStatement.class),timeout=mock(PreparedStatement.class),
            valueQuery=mock(PreparedStatement.class),defaultQuery=mock(PreparedStatement.class);
        final ResultSet serverRows=mock(ResultSet.class),valueRows=mock(ResultSet.class),defaultRows=mock(ResultSet.class);
        Object pool,driver;
        java.util.Properties driverProperties;
        @SuppressWarnings({"unchecked","rawtypes"})
        void decimalFence() throws Exception {
            when(metadata.getColumnType(2)).thenReturn(Types.DECIMAL);when(attributes.getString(5)).thenReturn("decimal(12,3)");
            Class contract=Class.forName("com.mysql.jdbc.Connection");driver=mock(contract);
            driverProperties=new java.util.Properties();when(contract.getMethod("getProperties").invoke(driver)).thenReturn(driverProperties);
            doReturn(driver).when(connection).unwrap(contract);
            pool=mock(manager.getClass().getMethod("getDataSource").getReturnType());
            when(manager.getClass().getMethod("getDataSource").invoke(manager)).thenReturn(pool);
            when(connection.prepareStatement("SHOW VARIABLES WHERE Variable_name IN ('innodb_table_locks','wsrep_on')")).thenReturn(serverQuery);
            when(serverQuery.executeQuery()).thenReturn(serverRows);when(serverRows.next()).thenReturn(true,false);
            when(serverRows.getString(1)).thenReturn("innodb_table_locks");when(serverRows.getString(2)).thenReturn("ON");
            when(connection.prepareStatement("SET SESSION lock_wait_timeout=5")).thenReturn(timeout);
            when(connection.prepareStatement("LOCK TABLES `users` WRITE")).thenReturn(lock);
            when(connection.prepareStatement("UNLOCK TABLES")).thenReturn(unlock);
            when(connection.prepareStatement(startsWith("SELECT 1 FROM"))).thenReturn(valueQuery);when(valueQuery.executeQuery()).thenReturn(valueRows);
            when(connection.prepareStatement("SELECT CAST(? AS DECIMAL(12,2))")).thenReturn(defaultQuery);
            when(defaultQuery.executeQuery()).thenReturn(defaultRows);when(defaultRows.next()).thenReturn(true,false);
            when(defaultRows.getBigDecimal(1)).thenReturn(new java.math.BigDecimal("7"));
        }
        void verifyEviction() throws Exception {pool.getClass().getMethod("evictConnection",Connection.class).invoke(verify(pool),connection);}
        Fixture(String declaration) throws Exception { this(new UserDataKeyString("RetainedValue").setColumnType(declaration)); }
        Fixture(UserDataKey key) throws Exception {
            super(key);
            when(connection.prepareStatement("SELECT @@SESSION.sql_mode")).thenReturn(modeQuery);
            when(connection.prepareStatement("SET SESSION sql_mode=?")).thenReturn(modeSet);
            when(modeQuery.executeQuery()).thenReturn(modeRows);when(modeRows.next()).thenReturn(true,false);when(modeRows.getString(1)).thenReturn("");
            when(metadata.getColumnCount()).thenReturn(2);when(metadata.getColumnName(2)).thenReturn("RetainedValue");
            when(metadata.getColumnType(2)).thenReturn(Types.INTEGER);
            when(connection.prepareStatement(startsWith("SELECT IS_NULLABLE"))).thenReturn(attributesQuery);
            when(attributesQuery.executeQuery()).thenReturn(attributes);
            java.util.concurrent.atomic.AtomicInteger attributeRows=new java.util.concurrent.atomic.AtomicInteger();
            when(attributes.next()).thenAnswer(call->attributeRows.getAndIncrement()%2==0);
            when(attributes.getString(1)).thenReturn("NO");when(attributes.getString(2)).thenReturn("7");
            when(attributes.getString(3)).thenReturn("");when(attributes.getString(4)).thenReturn("legacy value");when(attributes.getString(5)).thenReturn("int(11)");
        }
    }
}

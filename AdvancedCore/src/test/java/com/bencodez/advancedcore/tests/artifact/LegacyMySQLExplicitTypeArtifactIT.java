package com.bencodez.advancedcore.tests.artifact;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import com.bencodez.simpleapi.sql.DataType;

class LegacyMySQLExplicitTypeArtifactIT {
    private static List<Runnable> queue(LegacyMySQLRetainedTypeArtifactIT.Fixture f) throws Exception {
        List<Runnable> tasks=new ArrayList<>();ExecutorService executor=mock(ExecutorService.class);
        when(executor.submit(any(Runnable.class))).thenAnswer(call->{tasks.add(call.getArgument(0));return mock(Future.class);});
        Object driver=f.storeType.getMethod("getMysql").invoke(f.store);
        when(driver.getClass().getMethod("getThreadPool").invoke(driver)).thenReturn(executor);
        return tasks;
    }
    private static boolean pending(LegacyMySQLRetainedTypeArtifactIT.Fixture f) throws Exception {
        java.lang.reflect.Field cache=f.storeType.getDeclaredField("reconciledStringColumns");cache.setAccessible(true);
        Object value=((Map<?,?>)cache.get(f.store)).get("retainedvalue");
        if(value==null)return false;
        java.lang.reflect.Field state=value.getClass().getDeclaredField("pending");state.setAccessible(true);return state.getBoolean(value);
    }
    @Test void pendingExplicitRequestSuppressesCompetingAutomaticConversionWithoutClaimingCompletion() throws Exception {
        try(LegacyMySQLRetainedTypeArtifactIT.Fixture f=new LegacyMySQLRetainedTypeArtifactIT.Fixture("TEXT")) {
            List<Runnable> tasks=queue(f);
            f.storeType.getMethod("alterColumnType",String.class,String.class).invoke(f.store,"RetainedValue","VARCHAR(30)");
            assertTrue(pending(f));f.check("RetainedValue",DataType.STRING);verify(f.statement,never()).executeUpdate();
            tasks.get(0).run();assertFalse(pending(f));verify(f.statement).executeUpdate();
        }
    }
    @Test void failedExplicitSqlDoesNotSettleAndNextAutomaticCheckReinspects() throws Exception {
        try(LegacyMySQLRetainedTypeArtifactIT.Fixture f=new LegacyMySQLRetainedTypeArtifactIT.Fixture("TEXT")) {
            List<Runnable> tasks=queue(f);
            f.storeType.getMethod("alterColumnType",String.class,String.class).invoke(f.store,"RetainedValue","VARCHAR(30)");
            when(f.statement.executeUpdate()).thenThrow(new SQLException("fixture explicit DDL failure")).thenReturn(0);
            tasks.get(0).run();assertFalse(pending(f));f.check("RetainedValue",DataType.STRING);
            verify(f.attributesQuery).executeQuery();verify(f.statement,times(2)).executeUpdate();
        }
    }
    @Test void statementCleanupFailureCannotSettleCommittedExplicitSql() throws Exception {
        try(LegacyMySQLRetainedTypeArtifactIT.Fixture f=new LegacyMySQLRetainedTypeArtifactIT.Fixture("TEXT")) {
            List<Runnable> tasks=queue(f);
            f.storeType.getMethod("alterColumnType",String.class,String.class).invoke(f.store,"RetainedValue","VARCHAR(30)");
            doThrow(new SQLException("fixture explicit cleanup failure")).when(f.statement).close();tasks.get(0).run();
            assertFalse(pending(f));doNothing().when(f.statement).close();f.check("RetainedValue",DataType.STRING);
            verify(f.attributesQuery).executeQuery();verify(f.statement,times(2)).executeUpdate();
        }
    }
    @Test void rejectedRequestPreservesPreviouslyCompletedExplicitOwnership() throws Exception {
        try(LegacyMySQLRetainedTypeArtifactIT.Fixture f=new LegacyMySQLRetainedTypeArtifactIT.Fixture("TEXT")) {
            List<Runnable> tasks=queue(f);
            f.storeType.getMethod("alterColumnType",String.class,String.class).invoke(f.store,"RetainedValue","VARCHAR(30)");tasks.get(0).run();
            Object driver=f.storeType.getMethod("getMysql").invoke(f.store);ExecutorService stopped=mock(ExecutorService.class);
            when(stopped.submit(any(Runnable.class))).thenThrow(new RejectedExecutionException("fixture stopped executor"));
            when(driver.getClass().getMethod("getThreadPool").invoke(driver)).thenReturn(stopped);
            assertThrows(java.lang.reflect.InvocationTargetException.class,()->f.storeType.getMethod("alterColumnType",String.class,String.class).invoke(f.store,"RetainedValue","VARCHAR(50)"));
            assertFalse(pending(f));f.check("RetainedValue",DataType.STRING);verifyNoInteractions(f.attributesQuery);verify(f.statement).executeUpdate();
        }
    }
    @Test void olderFailureDoesNotCancelNewerQueuedExplicitOwnership() throws Exception {
        try(LegacyMySQLRetainedTypeArtifactIT.Fixture f=new LegacyMySQLRetainedTypeArtifactIT.Fixture("TEXT")) {
            List<Runnable> tasks=queue(f);
            f.storeType.getMethod("alterColumnType",String.class,String.class).invoke(f.store,"RetainedValue","VARCHAR(30)");
            f.storeType.getMethod("alterColumnType",String.class,String.class).invoke(f.store,"RetainedValue","VARCHAR(50)");
            when(f.statement.executeUpdate()).thenThrow(new SQLException("fixture older DDL failure")).thenReturn(0);
            tasks.get(0).run();assertTrue(pending(f));f.check("RetainedValue",DataType.STRING);verifyNoInteractions(f.attributesQuery);
            tasks.get(1).run();assertFalse(pending(f));verify(f.statement,times(2)).executeUpdate();
        }
    }
    @Test void missingColumnIsCreatedBeforeTheExplicitRequestIsQueued() throws Exception {
        try(LegacyMySQLRetainedTypeArtifactIT.Fixture f=new LegacyMySQLRetainedTypeArtifactIT.Fixture("VARCHAR(20)")) {
            when(f.metadata.getColumnCount()).thenReturn(1);List<Runnable> tasks=queue(f);
            f.storeType.getMethod("alterColumnType",String.class,String.class).invoke(f.store,"RetainedValue","VARCHAR(30)");
            verify(f.connection).prepareStatement("ALTER TABLE users ADD COLUMN `RetainedValue` VARCHAR(20);");
            verify(f.statement).executeUpdate();assertEquals(1,tasks.size());assertTrue(pending(f));
            tasks.get(0).run();verify(f.statement,times(2)).executeUpdate();assertFalse(pending(f));
        }
    }
    @Test void workerCannotImplicitlyCommitBorrowedOuterTransaction() throws Exception {
        try(LegacyMySQLRetainedTypeArtifactIT.Fixture f=new LegacyMySQLRetainedTypeArtifactIT.Fixture("TEXT")) {
            List<Runnable> tasks=queue(f);
            f.storeType.getMethod("alterColumnType",String.class,String.class).invoke(f.store,"RetainedValue","VARCHAR(30)");
            when(f.connection.getAutoCommit()).thenReturn(false);tasks.get(0).run();
            verify(f.statement,never()).executeUpdate();verify(f.connection,never()).commit();verify(f.connection,never()).setAutoCommit(anyBoolean());assertFalse(pending(f));
        }
    }
    @Test void workerCallbackFailureCannotLeavePendingOwnershipBehind() throws Exception {
        try(LegacyMySQLRetainedTypeArtifactIT.Fixture f=new LegacyMySQLRetainedTypeArtifactIT.Fixture("TEXT")) {
            List<Runnable> tasks=queue(f);f.set("columns",new ArrayList<>(Arrays.asList("uuid","RetainedValue")));
            java.lang.reflect.Field pluginField=f.storeType.getDeclaredField("plugin");pluginField.setAccessible(true);
            com.bencodez.advancedcore.AdvancedCorePlugin plugin=(com.bencodez.advancedcore.AdvancedCorePlugin)pluginField.get(f.store);
            doThrow(new IllegalStateException("fixture worker callback failure")).when(plugin).debug(anyString());
            f.storeType.getMethod("alterColumnType",String.class,String.class).invoke(f.store,"RetainedValue","VARCHAR(30)");
            assertTrue(pending(f));tasks.get(0).run();assertFalse(pending(f));verify(f.statement,never()).executeUpdate();
        }
    }
    @Test void explicitTypeDoesNotFirstApplyRegisteredDefinition() throws Exception {
        try(LegacyMySQLRetainedTypeArtifactIT.Fixture f=new LegacyMySQLRetainedTypeArtifactIT.Fixture("TEXT")) {
            List<Runnable> tasks=queue(f);
            f.storeType.getMethod("alterColumnType",String.class,String.class).invoke(f.store,"RetainedValue","VARCHAR(30)");
            assertEquals(1,tasks.size());verify(f.statement,never()).executeUpdate();verifyNoInteractions(f.attributesQuery);
            tasks.get(0).run();verify(f.connection).prepareStatement("ALTER TABLE users MODIFY `RetainedValue` VARCHAR(30);");
            verify(f.statement).executeUpdate();
            f.check("RetainedValue",DataType.STRING);verifyNoInteractions(f.attributesQuery);
        }
    }
}

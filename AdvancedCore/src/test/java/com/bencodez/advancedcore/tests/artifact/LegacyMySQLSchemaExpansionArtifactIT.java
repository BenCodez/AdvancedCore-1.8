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
    static class Fixture extends LegacyMySQLCheckedWriteArtifactIT.Fixture {
        Fixture(UserDataKey key) throws Exception {
            set("object3",new Object());set("columns",new ArrayList<>(Collections.singletonList("uuid")));
            AdvancedCorePlugin plugin=mock(AdvancedCorePlugin.class,RETURNS_DEEP_STUBS);set("plugin",plugin);
            when(plugin.getUserManager().getDataManager().getRegisteredKeysSnapshot()).thenReturn(new ArrayList<>(Collections.singletonList(key)));
        }
        void add(String column,DataType type) throws Exception {storeType.getMethod("addColumn",String.class,DataType.class).invoke(store,column,type);}
        @SuppressWarnings("unchecked") List<String> columns() throws Exception {return (List<String>)storeType.getMethod("getColumns").invoke(store);}
    }
}

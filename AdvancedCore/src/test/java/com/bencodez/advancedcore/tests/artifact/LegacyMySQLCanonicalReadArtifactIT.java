package com.bencodez.advancedcore.tests.artifact;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.user.usercache.UserDataManager;
import com.bencodez.advancedcore.api.user.usercache.keys.*;
import com.bencodez.simpleapi.sql.Column;

class LegacyMySQLCanonicalReadArtifactIT {
    @Test void retainedPhysicalCaseAliasPreservesRegisteredIntegerNameAndValue() throws Exception {
        try(Fixture f=new Fixture(new UserDataKeyInt("Points"),"points","17")) {
            when(f.dataManager.isInt("Points")).thenReturn(true);when(f.rows.getInt(2)).thenReturn(17);
            List<Column> values=f.read();assertEquals("Points",values.get(1).getName());
            assertTrue(values.get(1).getValue().isInt());assertEquals(17,values.get(1).getValue().getInt());
            assertEquals("Custom",values.get(2).getName());assertEquals("raw",values.get(2).getValue().getString());
        }
    }
    @Test void retainedPhysicalCaseAliasPreservesRegisteredBooleanNameAndValue() throws Exception {
        try(Fixture f=new Fixture(new UserDataKeyBoolean("Enabled"),"enabled","true")) {
            when(f.dataManager.isBoolean("Enabled")).thenReturn(true);
            List<Column> values=f.read();assertEquals("Enabled",values.get(1).getName());
            assertTrue(values.get(1).getValue().isBoolean());assertTrue(values.get(1).getValue().getBoolean());
        }
    }
    @Test void legacySingleAndBulkReadsUseTheSameRegisteredProjection() throws Exception {
        for(String method:new String[]{"getExact","getAllQuery","getAllQueryStrict"}) {
            try(Fixture f=new Fixture(new UserDataKeyInt("Points"),"points","17")) {
                when(f.dataManager.isInt("Points")).thenReturn(true);when(f.rows.getInt(2)).thenReturn(17);
                Object result=method.equals("getExact")?f.storeType.getMethod(method,String.class).invoke(f.store,f.uuid)
                    :f.storeType.getMethod(method).invoke(f.store);
                @SuppressWarnings("unchecked") List<Column> values=result instanceof Map
                    ?((Map<UUID,ArrayList<Column>>)result).get(UUID.fromString(f.uuid)):(List<Column>)result;
                assertEquals("Points",values.get(1).getName(),method);assertEquals(17,values.get(1).getValue().getInt(),method);
                assertEquals("Custom",values.get(2).getName(),method);verify(f.dataManager,times(1)).getRegisteredKeysSnapshot();
            }
        }
    }
    static class Fixture extends LegacyMySQLCheckedWriteArtifactIT.Fixture {
        final ResultSet rows=mock(ResultSet.class);final ResultSetMetaData metadata=mock(ResultSetMetaData.class);
        final UserDataManager dataManager=mock(UserDataManager.class);
        Fixture(UserDataKey key,String physicalName,String stored) throws Exception {
            AdvancedCorePlugin plugin=mock(AdvancedCorePlugin.class,RETURNS_DEEP_STUBS);set("plugin",plugin);
            when(plugin.getUserManager().getDataManager()).thenReturn(dataManager);
            when(dataManager.getRegisteredKeysSnapshot()).thenReturn(new ArrayList<>(Collections.singletonList(key)));
            when(statement.executeQuery()).thenReturn(rows);when(rows.next()).thenReturn(true,false);when(rows.getMetaData()).thenReturn(metadata);
            when(metadata.getColumnCount()).thenReturn(3);when(metadata.getColumnLabel(1)).thenReturn("uuid");when(metadata.getColumnLabel(2)).thenReturn(physicalName);when(metadata.getColumnLabel(3)).thenReturn("Custom");
            when(rows.getString(1)).thenReturn(uuid);when(rows.getString(2)).thenReturn(stored);when(rows.getString(3)).thenReturn("raw");
        }
        @SuppressWarnings("unchecked") List<Column> read() throws Exception {return (List<Column>)storeType.getMethod("getExactStrict",String.class).invoke(store,uuid);}
    }
}

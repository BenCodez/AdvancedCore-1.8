package com.bencodez.advancedcore.api.user.userstorage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.api.user.usercache.UserDataManager;
import com.bencodez.simpleapi.sql.Column;

class LegacyCompleteUserRowsTest {
    final String id="8d17c3ab-1085-4e22-95ad-8c975f3e4c24";
    @Test void validLegacyTypesAndBooleanRepresentationsArePreserved() throws Exception {
        UserDataManager types=mock(UserDataManager.class);when(types.isInt("Points")).thenReturn(true);when(types.isBoolean("Enabled")).thenReturn(true);
        ResultSet rows=rows(new String[]{"uuid","Points","Enabled","Message"},new String[][]{{id,"7","true","O'Brien"}});
        ArrayList<Column> values=CompleteUserRows.read(rows,types).get(UUID.fromString(id));assertEquals(4,values.size());assertEquals(7,values.get(1).getValue().getInt());assertTrue(values.get(2).getValue().getBoolean());assertEquals("O'Brien",values.get(3).getValue().getString());
        for(String bool:new String[]{"true","false","1","0"}) assertEquals(bool.equals("true")||bool.equals("1"),CompleteUserRows.read(rows(new String[]{"uuid","Enabled"},new String[][]{{id,bool}}),types).get(UUID.fromString(id)).get(1).getValue().getBoolean());
    }
    @Test void malformedIntegersAndBooleansCannotBeConvertedToDefaults() throws Exception {
        UserDataManager types=mock(UserDataManager.class);when(types.isInt("Points")).thenReturn(true);when(types.isBoolean("Enabled")).thenReturn(true);
        assertThrows(SQLException.class,() -> CompleteUserRows.read(rows(new String[]{"uuid","Points"},new String[][]{{id,"not a number"}}),types));
        assertThrows(SQLException.class,() -> CompleteUserRows.read(rows(new String[]{"uuid","Enabled"},new String[][]{{id,"maybe"}}),types));
    }
    @Test void duplicateNormalizedOrMalformedIdentityRejectsWholeSource() throws Exception {
        UserDataManager types=mock(UserDataManager.class);
        assertThrows(SQLException.class,() -> CompleteUserRows.read(rows(new String[]{"uuid"},new String[][]{{id},{id.toUpperCase(Locale.ROOT)}}),types));
        for(String malformed:new String[]{null,"bad","1-1-1-1-1"})assertThrows(SQLException.class,() -> CompleteUserRows.read(rows(new String[]{"uuid"},new String[][]{{malformed}}),types));
    }
    @Test void missingOrRepeatedColumnIdentityIsNotAnEmptyHealthySource() throws Exception {
        UserDataManager types=mock(UserDataManager.class);
        assertThrows(SQLException.class,() -> CompleteUserRows.read(rows(new String[]{"Points"},new String[0][]),types));
        assertThrows(SQLException.class,() -> CompleteUserRows.read(rows(new String[]{"uuid","uuid"},new String[0][]),types));
    }
    @Test void sqlNullTypedDefaultsAndEmptyValidSourceRemainCompatible() throws Exception {
        UserDataManager types=mock(UserDataManager.class);when(types.isInt("Points")).thenReturn(true);when(types.isBoolean("Enabled")).thenReturn(true);
        ArrayList<Column> values=CompleteUserRows.read(rows(new String[]{"uuid","Points","Enabled"},new String[][]{{id,null,null}}),types).get(UUID.fromString(id));assertEquals(0,values.get(1).getValue().getInt());assertFalse(values.get(2).getValue().getBoolean());
        assertTrue(CompleteUserRows.read(rows(new String[]{"uuid"},new String[0][]),types).isEmpty());
    }
    ResultSet rows(String[] labels,String[][] data)throws Exception {
        ResultSet rows=mock(ResultSet.class);ResultSetMetaData meta=mock(ResultSetMetaData.class);when(rows.getMetaData()).thenReturn(meta);when(meta.getColumnCount()).thenReturn(labels.length);
        for(int i=0;i<labels.length;i++)when(meta.getColumnLabel(i+1)).thenReturn(labels[i]);
        java.util.concurrent.atomic.AtomicInteger index=new java.util.concurrent.atomic.AtomicInteger(-1);when(rows.next()).thenAnswer(call -> index.incrementAndGet()<data.length);
        when(rows.getString(anyInt())).thenAnswer(call -> data[index.get()][(Integer)call.getArgument(0)-1]);return rows;
    }
}

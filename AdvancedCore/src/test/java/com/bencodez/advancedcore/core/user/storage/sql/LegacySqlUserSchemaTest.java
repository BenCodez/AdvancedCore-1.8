package com.bencodez.advancedcore.core.user.storage.sql;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.api.user.usercache.keys.*;
import com.bencodez.simpleapi.sql.DataType;

class LegacySqlUserSchemaTest {
    @Test void schemaSnapshotsBuilderMembershipAndReturnsDetachedOrderedColumns() {
        SqlUserSchema.Builder builder=SqlUserSchema.builder().column("Points","INT",DataType.INTEGER);
        SqlUserSchema first=builder.build();builder.column("Name","TEXT",DataType.STRING);
        List<SqlUserSchema.ColumnDefinition> copy=first.columns();assertEquals("uuid",copy.get(0).name());assertEquals("Points",copy.get(1).name());copy.clear();
        assertEquals(2,first.columns().size());assertFalse(first.contains("Name"));assertEquals(3,builder.build().columns().size());
        assertEquals("VARCHAR(37)",first.column("UUID").sqlType());assertEquals(DataType.INTEGER,first.column("pOiNtS").dataType());
        assertNull(first.column(null));
    }
    @Test void identityAndCaseDuplicatesAreRejectedWithoutReplacingValidDefinitions() {
        SqlUserSchema.Builder builder=SqlUserSchema.builder().column("Points","INT",DataType.INTEGER);
        assertThrows(IllegalArgumentException.class,()->builder.column("points","TEXT",DataType.STRING));
        assertThrows(IllegalArgumentException.class,()->builder.column("UUID","TEXT",DataType.STRING));
        assertEquals("INT",builder.build().column("points").sqlType());assertEquals(2,builder.build().columns().size());
    }
    @Test void localeIndependentLookupAndTypedKeyDeclarationsAreRetained() {
        Locale previous=Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr","TR"));
            SqlUserSchema schema=SqlUserSchema.fromKeys(Arrays.asList(new UserDataKeyInt("IDENTITY"),new UserDataKeyBoolean("Flag"),new UserDataKeyString("Text").setColumnType("VARCHAR(20)")));
            assertEquals(DataType.INTEGER,schema.column("identity").dataType());assertEquals("INT DEFAULT '0'",schema.column("identity").sqlType());
            assertEquals(DataType.BOOLEAN,schema.column("flag").dataType());assertEquals("VARCHAR(20)",schema.column("text").sqlType());
        } finally {Locale.setDefault(previous);}
    }
    @Test void java8ValueTypePreservesRecordStyleAccessorsEqualityAndText() {
        SqlUserSchema.ColumnDefinition first=new SqlUserSchema.ColumnDefinition("Name","TEXT",DataType.STRING),equal=new SqlUserSchema.ColumnDefinition("Name","TEXT",DataType.STRING);
        assertEquals(first,equal);assertEquals(first.hashCode(),equal.hashCode());assertNotEquals(first,new SqlUserSchema.ColumnDefinition("Name","INT",DataType.INTEGER));assertNotEquals(first,null);
        assertEquals("Name",first.name());assertEquals("TEXT",first.sqlType());assertEquals(DataType.STRING,first.dataType());
        assertEquals("ColumnDefinition[name=Name, sqlType=TEXT, dataType=STRING]",first.toString());
    }
    @Test void invalidNullAndUnicodeBlankDefinitionsAreRejected() {
        for(String blank:Arrays.asList(""," ","\t\n","\u2003")) {
            assertThrows(IllegalArgumentException.class,()->new SqlUserSchema.ColumnDefinition(blank,"TEXT",DataType.STRING));
            assertThrows(IllegalArgumentException.class,()->new SqlUserSchema.ColumnDefinition("Name",blank,DataType.STRING));
        }
        assertThrows(NullPointerException.class,()->new SqlUserSchema.ColumnDefinition(null,"TEXT",DataType.STRING));
        assertThrows(NullPointerException.class,()->new SqlUserSchema.ColumnDefinition("Name","TEXT",null));
        assertThrows(NullPointerException.class,()->SqlUserSchema.fromKeys(null));
    }
    @Test void metadataCallbackCannotExpandTheCurrentSchemaGeneration() {
        List<UserDataKey> keys=new ArrayList<>();AtomicBoolean added=new AtomicBoolean();
        keys.add(new UserDataKeyString("First") {
            @Override public String getColumnType() {
                if(added.compareAndSet(false,true))keys.add(new UserDataKeyInt("Late"));
                return super.getColumnType();
            }
        });
        SqlUserSchema first=SqlUserSchema.fromKeys(keys);assertEquals(2,first.columns().size());assertFalse(first.contains("Late"));
        SqlUserSchema second=SqlUserSchema.fromKeys(keys);assertEquals(3,second.columns().size());assertEquals(DataType.INTEGER,second.column("Late").dataType());
    }
}

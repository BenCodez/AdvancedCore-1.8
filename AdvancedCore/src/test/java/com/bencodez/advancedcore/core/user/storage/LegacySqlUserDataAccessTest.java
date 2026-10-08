package com.bencodez.advancedcore.core.user.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.api.user.UserStorage;
import com.bencodez.simpleapi.sql.Column;
import com.bencodez.simpleapi.sql.data.*;

class LegacySqlUserDataAccessTest {
    @Test void constructionIsLazyAndOverrideRowReaderRemainsLive() {
        SqlUserStorage storage=mock(SqlUserStorage.class);AtomicInteger reads=new AtomicInteger();
        SqlUserDataAccess access=new SqlUserDataAccess(storage,backend->{assertEquals(UserStorage.MYSQL,backend);return Arrays.asList(new Column("Points",new DataValueInt(reads.incrementAndGet())));});
        assertEquals(0,reads.get());verifyNoInteractions(storage);
        assertEquals(1,access.getInt(UserStorage.MYSQL,"Points",0));assertEquals(2,access.getInt(UserStorage.MYSQL,"Points",0));
        verifyNoInteractions(storage);
    }
    @Test void integersAndInvalidFirstDuplicatePreserveLegacyDefaults() {
        SqlUserStorage storage=mock(SqlUserStorage.class);SqlUserDataAccess access=new SqlUserDataAccess(storage);
        when(storage.readRow(UserStorage.SQLITE)).thenReturn(Arrays.asList(new Column("Points",new DataValueString("17"))));
        assertEquals(17,access.getInt(UserStorage.SQLITE,"Points",4));
        when(storage.readRow(UserStorage.SQLITE)).thenReturn(Arrays.asList(new Column("Points",new DataValueString("invalid")),new Column("Points",new DataValueInt(99))));
        assertEquals(4,access.getInt(UserStorage.SQLITE,"Points",4));assertEquals(4,access.getInt(UserStorage.SQLITE,"points",4));
    }
    @Test void blankKeysAvoidReadsAndBooleanStringsAndNullSentinelStayCompatible() {
        SqlUserStorage storage=mock(SqlUserStorage.class);SqlUserDataAccess access=new SqlUserDataAccess(storage);
        assertEquals(3,access.getInt(UserStorage.MYSQL,"",3));assertEquals("",access.getString(UserStorage.MYSQL,null));verifyNoInteractions(storage);
        when(storage.readRow(UserStorage.MYSQL)).thenReturn(Arrays.asList(new Column("Flag",new DataValueBoolean(true)),new Column("Missing",new DataValueString("NuLl"))));
        assertEquals("true",access.getString(UserStorage.MYSQL,"Flag"));assertEquals("",access.getString(UserStorage.MYSQL,"Missing"));
    }
    @Test void conversionIsDetachedLastWinsAndKeepsValueObjectsAndDuplicateKeys() {
        DataValueString one=new DataValueString("one"),two=new DataValueString("two");
        List<Column> row=new ArrayList<>(Arrays.asList(new Column("Key",one),new Column("Key",two)));
        HashMap<String,DataValue> converted=SqlUserDataAccess.convert(row);assertSame(two,converted.get("Key"));row.clear();assertEquals(1,converted.size());
        SqlUserStorage storage=mock(SqlUserStorage.class);when(storage.readRow(UserStorage.SQLITE)).thenReturn(Arrays.asList(new Column("Key",one),new Column("Key",two)));
        assertEquals(Arrays.asList("Key","Key"),new SqlUserDataAccess(storage).getKeys(UserStorage.SQLITE));assertTrue(SqlUserDataAccess.convert(null).isEmpty());
    }
    @Test void providerFailuresRemainVisibleAndWritesDoNotBecomeAdditionalAcknowledgements() {
        SqlUserStorage storage=mock(SqlUserStorage.class);SqlUserDataAccess access=new SqlUserDataAccess(storage);IllegalStateException failure=new IllegalStateException("unavailable");
        when(storage.readRow(UserStorage.MYSQL)).thenThrow(failure);assertSame(failure,assertThrows(IllegalStateException.class,()->access.getValues(UserStorage.MYSQL)));
        access.setInt(UserStorage.SQLITE,"Points",8);verify(storage).write(eq(UserStorage.SQLITE),eq("Points"),any(DataValueInt.class));
        HashMap<String,DataValue> values=new HashMap<>();values.put("Key",new DataValueString("retained"));access.setValues(UserStorage.SQLITE,values);verify(storage).writeValues(UserStorage.SQLITE,values);
        doThrow(failure).when(storage).delete(UserStorage.SQLITE);assertSame(failure,assertThrows(IllegalStateException.class,()->access.remove(UserStorage.SQLITE)));
    }
    @Test void atomicTransactionIsExplicitlyUnsupportedWithoutAnActualBackend() {
        SqlUserStorage storage=mock(SqlUserStorage.class,CALLS_REAL_METHODS);AtomicInteger effects=new AtomicInteger();
        assertThrows(UnsupportedOperationException.class,()->storage.transaction(UserStorage.SQLITE,scope->{effects.incrementAndGet();return null;}));assertEquals(0,effects.get());
    }
}

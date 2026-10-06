package com.bencodez.advancedcore.api.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.user.userstorage.mysql.MySQL;
import com.bencodez.advancedcore.api.user.userstorage.sql.UserTable;
import com.bencodez.advancedcore.thread.FileThread;
import com.bencodez.simpleapi.sql.Column;
import com.bencodez.simpleapi.sql.data.*;

class LegacyCheckedUserDataAdapterTest {
    private static final String UUID="00000000-0000-0000-0000-000000000001";
    @Test void mysqlBatchUsesCheckedMethodAndDoesNotMutateCallerMap() throws Exception {
        Fixture f=new Fixture(UserStorage.MYSQL);MySQL mysql=mock(MySQL.class);when(f.plugin.getMysql()).thenReturn(mysql);
        Map<String,DataValue> values=new HashMap<>();values.put("uuid",new DataValueString("ignored"));values.put("Points",new DataValueInt(7));
        f.data.setValuesStrict(values);ArgumentCaptor<List<Column>> captured=ArgumentCaptor.forClass(List.class);
        verify(mysql).updateStrict(eq(UUID),captured.capture());assertEquals(1,captured.getValue().size());
        assertEquals("Points",captured.getValue().get(0).getName());assertEquals(7,captured.getValue().get(0).getValue().getInt());
        assertEquals(2,values.size());verify(mysql,never()).update(anyString(),anyList(),anyBoolean());
    }
    @Test void sqliteBatchUsesCheckedMethodAndPropagatesFailure() throws Exception {
        Fixture f=new Fixture(UserStorage.SQLITE);UserTable table=mock(UserTable.class);when(f.plugin.getSQLiteUserTable()).thenReturn(table);
        SQLException failure=new SQLException("database rejected batch");doThrow(failure).when(table).updateStrict(any(),anyList());
        assertSame(failure,assertThrows(SQLException.class,
            ()->f.data.setValuesStrict(Collections.singletonMap("Points",new DataValueInt(7)))));
        verify(table,never()).update(any(),anyList());
    }
    @Test void flatBatchUsesTheExistingOwnerWithoutStartingPollingThread() throws Exception {
        Fixture f=new Fixture(UserStorage.FLAT);FileThread owner=mock(FileThread.class);Map<String,DataValue> values=Collections.singletonMap("Points",new DataValueInt(7));
        try(MockedStatic<FileThread> files=mockStatic(FileThread.class)) {
            files.when(FileThread::getInstance).thenReturn(owner);f.data.setValuesStrict(values);
            verify(owner).setValuesStrict(UUID,values);verify(owner,never()).getThread();
            IOException failure=new IOException("file publication rejected");doThrow(failure).when(owner).setValuesStrict(UUID,values);
            assertSame(failure,assertThrows(IOException.class,()->f.data.setValuesStrict(values)));
        }
    }
    @Test void missingStorageIsAFailureAndEmptyBatchesRemainNoOps() throws Exception {
        Fixture mysql=new Fixture(UserStorage.MYSQL);
        assertThrows(SQLException.class,()->mysql.data.setValuesStrict(Collections.singletonMap("Points",new DataValueInt(7))));
        Fixture sqlite=new Fixture(UserStorage.SQLITE);
        assertThrows(SQLException.class,()->sqlite.data.setValuesStrict(Collections.singletonMap("Points",new DataValueInt(7))));
        Fixture unknown=new Fixture(null);unknown.data.setValuesStrict(Collections.emptyMap());
        assertThrows(IllegalStateException.class,()->unknown.data.setValuesStrict(Collections.singletonMap("Points",new DataValueInt(7))));
    }
    private static class Fixture {
        final AdvancedCorePlugin plugin=mock(AdvancedCorePlugin.class);
        final AdvancedCoreUser user=mock(AdvancedCoreUser.class);
        final UserData data=new UserData(user);
        Fixture(UserStorage storage) {when(user.getPlugin()).thenReturn(plugin);when(user.getUUID()).thenReturn(UUID);when(plugin.getStorageType()).thenReturn(storage);}
    }
}

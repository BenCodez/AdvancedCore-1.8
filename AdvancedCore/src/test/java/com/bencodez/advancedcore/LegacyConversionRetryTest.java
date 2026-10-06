package com.bencodez.advancedcore;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.api.user.*;
import com.bencodez.advancedcore.api.user.usercache.*;
import com.bencodez.advancedcore.api.user.userstorage.mysql.MySQL;
import com.bencodez.simpleapi.sql.Column;
import com.bencodez.simpleapi.sql.data.*;

class LegacyConversionRetryTest {
    @Test void failedPrefixCopyKeepsSourceAndExplicitRetryAssignsRatherThanAdds() throws Exception {
        AdvancedCorePlugin plugin=mock(AdvancedCorePlugin.class);UserStorageOwnership owner=new UserStorageOwnership();when(plugin.getUserStorageOwnership()).thenReturn(owner);when(plugin.getStorageType()).thenReturn(UserStorage.MYSQL);
        UserManager users=mock(UserManager.class);when(plugin.getUserManager()).thenReturn(users);UserDataManager data=mock(UserDataManager.class);
        when(users.getDataManager()).thenReturn(data);when(data.getPlugin()).thenReturn(plugin);when(data.getUserDataCache()).thenReturn(new ConcurrentHashMap<>());doCallRealMethod().when(data).writeBatch(any(),any(),any(),anyBoolean());
        UUID first=UUID.fromString("00000000-0000-0000-0000-000000000001"),second=UUID.fromString("00000000-0000-0000-0000-000000000002");
        HashMap<UUID,ArrayList<Column>> source=new LinkedHashMap<>();source.put(first,new ArrayList<>(Collections.singletonList(new Column("Points",new DataValueInt(19)))));source.put(second,new ArrayList<>(Collections.singletonList(new Column("Points",new DataValueInt(23)))));
        when(users.getAllKeysStrict(UserStorage.FLAT)).thenReturn(source);
        for(UUID id:source.keySet()) {AdvancedCoreUser user=mock(AdvancedCoreUser.class);when(user.getPlugin()).thenReturn(plugin);when(user.getUUID()).thenReturn(id.toString());when(user.getData()).thenReturn(new UserData(user));when(users.getUser(id,false)).thenReturn(user);}
        MySQL target=mock(MySQL.class);when(plugin.getMysql()).thenReturn(target);Map<UUID,Integer> committed=new HashMap<>();SQLException failed=new SQLException("fixture target failed");java.util.concurrent.atomic.AtomicBoolean reject=new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(call -> {UUID id=UUID.fromString(call.getArgument(0));if(id.equals(second)&&reject.get())throw failed;List<Column> cols=call.getArgument(1);committed.put(id,cols.get(0).getValue().getInt());return null;}).when(target).updateStrict(anyString(),anyList());
        doCallRealMethod().when(plugin).convertDataStorage(any(),any());when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        IllegalStateException outcome=assertThrows(IllegalStateException.class,() -> plugin.convertDataStorage(UserStorage.FLAT,UserStorage.MYSQL));assertSame(failed,outcome.getCause());assertEquals(Collections.singletonMap(first,19),committed);assertThrows(IllegalStateException.class,owner::admit);
        assertEquals(19,source.get(first).get(0).getValue().getInt());assertEquals(23,source.get(second).get(0).getValue().getInt());verify(target,never()).close();
        reject.set(false);plugin.convertDataStorage(UserStorage.FLAT,UserStorage.MYSQL);assertEquals(19,committed.get(first).intValue());assertEquals(23,committed.get(second).intValue());assertEquals(2,committed.size());
        try(UserStorageOwnership.Scope normal=owner.admit()) {}
        verify(target,times(2)).updateStrict(eq(first.toString()),anyList());verify(target,times(2)).updateStrict(eq(second.toString()),anyList());verify(users,never()).onChange(any(),anyString());
    }
}

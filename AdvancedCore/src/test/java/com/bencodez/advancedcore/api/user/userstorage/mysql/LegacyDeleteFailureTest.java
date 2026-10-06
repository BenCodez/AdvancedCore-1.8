package com.bencodez.advancedcore.api.user.userstorage.mysql;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import com.bencodez.advancedcore.api.misc.PlayerManager;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.simpleapi.sql.mysql.queries.Query;

class LegacyDeleteFailureTest {
    @Test void failedLegacyDeleteRetainsIdentityCaches() throws Exception {
        assertFailure(false);
    }
    @Test void strictDeletePropagatesFailureAndRetainsIdentityCaches() throws Exception {
        assertFailure(true);
    }
    @Test void committedDeleteEvictsBothIdentityCaches() throws Exception {
        String uuid = "00000000-0000-0000-0000-000000000001";
        MySQL store = mock(MySQL.class, CALLS_REAL_METHODS);
        Set<String> ids = new HashSet<>(); ids.add(uuid);
        Set<String> names = new HashSet<>(); names.add("ExistingPlayer");
        set(store,"plugin",mock(AdvancedCorePlugin.class, RETURNS_DEEP_STUBS));
        set(store,"uuids",ids); set(store,"names",names); set(store,"name","users");
        doNothing().when(store).clearCacheBasic();
        PlayerManager manager = mock(PlayerManager.class);
        when(manager.getPlayerName(any(),eq(uuid))).thenReturn("ExistingPlayer");
        try (MockedConstruction<Query> queries = mockConstruction(Query.class);
                MockedStatic<PlayerManager> global = mockStatic(PlayerManager.class)) {
            global.when(PlayerManager::getInstance).thenReturn(manager);
            store.deletePlayerStrict(uuid);
            assertEquals(1,queries.constructed().size());
            verify(queries.constructed().get(0)).executeUpdate();
            assertTrue(ids.isEmpty()); assertTrue(names.isEmpty());
            verify(store).clearCacheBasic();
        }
    }
    private void assertFailure(boolean strict) throws Exception {
        MySQL store = mock(MySQL.class, CALLS_REAL_METHODS);
        Set<String> ids = new HashSet<>(); ids.add("00000000-0000-0000-0000-000000000001");
        Set<String> names = new HashSet<>(); names.add("ExistingPlayer");
        set(store,"plugin",mock(AdvancedCorePlugin.class));
        set(store,"uuids",ids); set(store,"names",names); set(store,"name","users");
        SQLException failure = new SQLException("fixture delete rejected");
        try (MockedConstruction<Query> queries = mockConstruction(Query.class,
                (query, context) -> when(query.executeUpdate()).thenThrow(failure))) {
            if (strict) {
                IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> store.deletePlayerStrict(ids.iterator().next()));
                assertSame(failure, thrown.getCause());
            } else { store.deletePlayer(ids.iterator().next()); }
            assertEquals(1, queries.constructed().size());
            assertEquals(1,ids.size()); assertTrue(names.contains("ExistingPlayer"));
            verify(store,never()).clearCacheBasic();
        }
    }
    private void set(Object target,String name,Object value) throws Exception {
        Field field = MySQL.class.getDeclaredField(name); field.setAccessible(true); field.set(target,value);
    }
}

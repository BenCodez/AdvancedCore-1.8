package com.bencodez.advancedcore.api.user.userstorage.mysql;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Field;
import java.sql.*;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.simpleapi.sql.mysql.queries.Query;

class LegacyDeleteFailureTest {
    private static final String ID = "00000000-0000-0000-0000-000000000001";
    @Test void failedLegacyDeleteRetainsIdentityCaches() throws Exception {
        MySQL store = mock(MySQL.class, CALLS_REAL_METHODS); Set<String> ids = new HashSet<>(); ids.add(ID);
        Set<String> names = new HashSet<>(); names.add("ExistingPlayer");
        set(store, "plugin", mock(AdvancedCorePlugin.class)); set(store, "uuids", ids); set(store, "names", names); set(store, "name", "users");
        SQLException failure = new SQLException("fixture delete rejected");
        try (MockedConstruction<Query> queries = mockConstruction(Query.class, (query, context) -> when(query.executeUpdate()).thenThrow(failure))) {
            store.deletePlayer(ID); assertEquals(1, queries.constructed().size()); assertEquals(1, ids.size());
            assertTrue(names.contains("ExistingPlayer")); verify(store, never()).clearCacheBasic();
        }
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = MySQL.class.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
}

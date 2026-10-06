package com.bencodez.advancedcore.api.user.userstorage.mysql;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Field;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.simpleapi.sql.Column;
import com.bencodez.simpleapi.sql.data.*;
import com.bencodez.simpleapi.sql.mysql.queries.Query;

class LegacyMySQLBoundValueTest {
    private static final String UUID = "00000000-0000-0000-0000-000000000001";
    @Test void bulkUpdateBindsQuotedTextAndPreservesLegacyBooleanText() throws Exception {
        MySQL store = fixture();
        try (MockedConstruction<Query> queries = mockConstruction(Query.class, (q,c) -> {
            assertEquals("UPDATE users SET `Message`=?, `Enabled`=? WHERE uuid=?;",c.arguments().get(1));
        })) {
            store.update(UUID,Arrays.asList(new Column("Message",new DataValueString("O'Brien")),
                new Column("Enabled",new DataValueBoolean(true))),false);
            Query query = queries.constructed().get(0);
            verify(query).setParameter(1,"O'Brien");
            verify(query).setParameter(2,"true");
            verify(query).setParameter(3,UUID);
            verify(query).executeUpdate();
        }
    }
    @Test void asynchronousUpdateBindsValuesBeforeDispatch() throws Exception {
        MySQL store = fixture();
        try (MockedConstruction<Query> queries = mockConstruction(Query.class)) {
            store.update(UUID,Collections.singletonList(new Column("Points",new DataValueInt(25))),true);
            Query query = queries.constructed().get(0);
            org.mockito.InOrder order = inOrder(query);
            order.verify(query).setParameter(1,25);
            order.verify(query).setParameter(2,UUID);
            order.verify(query).executeUpdateAsync();
        }
    }
    @Test void singleBooleanUpdateRetainsExistingTextStorageFormat() throws Exception {
        MySQL store = fixture();
        try (MockedConstruction<Query> queries = mockConstruction(Query.class, (q,c) -> {
            assertEquals("UPDATE users SET `Enabled`=? WHERE uuid=?;",c.arguments().get(1));
        })) {
            store.update(UUID,"Enabled",new DataValueBoolean(true));
            Query query=queries.constructed().get(0);
            verify(query).setParameter(1,"true"); verify(query).setParameter(2,UUID);
            verify(query).executeUpdate();
        }
    }
    @Test void insertedNameAndValuesNeverBecomeSQLSyntax() throws Exception {
        MySQL store=fixture();
        try (MockedConstruction<Query> queries = mockConstruction(Query.class, (q,c) -> {
            assertEquals("INSERT IGNORE INTO users (`uuid`, `PlayerName`, `Enabled`) VALUES (?, ?, ?);",c.arguments().get(1));
        })) {
            store.insertQuery(UUID,Arrays.asList(new Column("PlayerName",new DataValueString("O'Brien")),
                new Column("Enabled",new DataValueBoolean(true))));
            Query query=queries.constructed().get(0);
            verify(query).setParameter(1,UUID);verify(query).setParameter(2,"O'Brien");
            verify(query).setParameter(3,"true");verify(query).executeUpdate();
        }
    }
    @Test void emptyUpdateDoesNotExecuteInvalidSQL() throws Exception {
        MySQL store=fixture();
        try(MockedConstruction<Query> queries=mockConstruction(Query.class)) {
            store.update(UUID,Collections.emptyList(),false);
            assertTrue(queries.constructed().isEmpty());
        }
    }
    private MySQL fixture() throws Exception {
        MySQL store=mock(MySQL.class,CALLS_REAL_METHODS);
        set(store,"plugin",mock(AdvancedCorePlugin.class,RETURNS_DEEP_STUBS));
        set(store,"name","users");set(store,"object2",new Object());
        set(store,"uuids",new HashSet<>(Collections.singletonList(UUID)));
        set(store,"names",new HashSet<String>());
        doNothing().when(store).checkColumn(anyString(),any());
        return store;
    }
    private void set(Object target,String name,Object value)throws Exception {
        Field field=MySQL.class.getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
}

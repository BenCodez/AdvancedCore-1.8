package com.bencodez.advancedcore.api.user.usercache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.user.*;
import com.bencodez.advancedcore.api.user.usercache.change.UserDataChangeString;
import com.bencodez.advancedcore.api.user.usercache.keys.UserDataKeyString;
import com.bencodez.simpleapi.sql.data.*;

class LegacyCacheRegistryTest {
    @Test void removalOfAbsentIdentityDoesNotCreateOrReadAUser() {
        Fixture f=new Fixture();f.manager.removeCache(f.id,null);
        verifyNoInteractions(f.data);assertTrue(f.manager.getUserDataCache().isEmpty());
    }
    @Test void removalDetachesBeforeCallbackAndCannotDiscardCallbackReplacement() throws Exception {
        Fixture f=new Fixture();UserDataCache old=f.existing();old.addChange(new UserDataChangeString("Points","old"),true);
        AtomicReference<UserDataCache> replacement=new AtomicReference<>();
        doAnswer(call->{UserDataCache next=f.manager.getCache(f.id);next.addChange(new UserDataChangeString("Points","new"),true);
            replacement.set(next);return null;}).when(f.users).onChange(eq(f.user),any(String[].class));
        f.manager.removeCache(f.id,null);
        assertNotSame(old,replacement.get());assertSame(replacement.get(),f.manager.getUserDataCache().get(f.id));
        assertEquals("new",replacement.get().getCachedValue("Points").getString());assertTrue(replacement.get().hasChangesToProcess());
        assertNull(old.getUuid());
    }
    @Test void bulkRemovalDoesNotClearANewerGenerationRegisteredByCallback() throws Exception {
        Fixture f=new Fixture();UserDataCache old=f.existing();old.addChange(new UserDataChangeString("Points","old"),true);
        UserDataCache replacement=new UserDataCache(f.manager,f.id);replacement.updateCache(values("new"));
        doAnswer(call->{f.manager.getUserDataCache().put(f.id,replacement);return null;})
            .when(f.users).onChange(eq(f.user),any(String[].class));
        f.manager.clearCache();assertSame(replacement,f.manager.getUserDataCache().get(f.id));
        assertEquals("new",replacement.getCachedValue("Points").getString());assertNull(old.getUuid());
    }
    @Test void slowPopulationCannotReplaceAnAlreadyPublishedGeneration() throws Exception {
        Fixture f=new Fixture();UserDataCache replacement=new UserDataCache(f.manager,f.id);replacement.updateCache(values("new"));
        doAnswer(call->{f.manager.getUserDataCache().put(f.id,replacement);return values("stale");}).when(f.data).getValuesStrict();
        f.manager.cacheUser(f.id);assertSame(replacement,f.manager.getUserDataCache().get(f.id));
    }
    @Test void failedRetirementRetainsCanonicalCacheAndUndumpedWork() throws Exception {
        Fixture f=new Fixture();UserDataCache old=f.existing();UserDataChangeString change=spy(new UserDataChangeString("Points","pending"));
        old.addChange(change,true);doThrow(new SQLException("unavailable")).when(f.data).setValuesStrict(any());
        assertThrows(IllegalStateException.class,()->f.manager.removeCache(f.id,null));
        assertSame(old,f.manager.getUserDataCache().get(f.id));assertNotNull(old.getUuid());assertTrue(old.hasChangesToProcess());
        verify(change,never()).dump();
    }
    @Test void retiredRegistryEntryIsReplacedOnLookup() {
        Fixture f=new Fixture();UserDataCache old=f.existing();old.dump();
        assertFalse(f.manager.containsKey(f.id));assertFalse(f.manager.isCached(f.id));
        UserDataCache next=f.manager.getCache(f.id);
        assertNotSame(old,next);assertSame(next,f.manager.getUserDataCache().get(f.id));
        assertEquals("stored",next.getCachedValue("Points").getString());
    }
    @Test void concurrentPopulationKeepsFirstPublishedGeneration() throws Exception {
        Fixture f=new Fixture();CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        doAnswer(call->{if(Thread.currentThread().getName().equals("slow-cache-reader")) {
            started.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return values("stale");
        }return values("new");}).when(f.data).getValuesStrict();
        ExecutorService executor=Executors.newSingleThreadExecutor(task->new Thread(task,"slow-cache-reader"));
        try {
            Future<UserDataCache> slow=executor.submit(()->f.manager.getCache(f.id));
            assertTrue(started.await(5,TimeUnit.SECONDS));UserDataCache winner=f.manager.getCache(f.id);
            release.countDown();assertSame(winner,slow.get(5,TimeUnit.SECONDS));
            assertSame(winner,f.manager.getUserDataCache().get(f.id));
            assertEquals("new",winner.getCachedValue("Points").getString());
        }finally {release.countDown();executor.shutdownNow();}
    }
    @Test void removalCallbackCanAwaitAnotherThreadsPopulation() throws Exception {
        Fixture f=new Fixture();UserDataCache old=f.existing();old.addChange(new UserDataChangeString("Points","old"),true);
        ExecutorService executor=Executors.newSingleThreadExecutor();AtomicReference<UserDataCache> next=new AtomicReference<>();
        try {
            doAnswer(call->{next.set(executor.submit(()->f.manager.getCache(f.id)).get(5,TimeUnit.SECONDS));return null;})
                .when(f.users).onChange(eq(f.user),any(String[].class));
            f.manager.removeCache(f.id,null);
            assertNotSame(old,next.get());assertSame(next.get(),f.manager.getUserDataCache().get(f.id));
        }finally {executor.shutdownNow();}
    }
    private static HashMap<String,DataValue> values(String value) {
        HashMap<String,DataValue> result=new HashMap<>();result.put("Points",new DataValueString(value));return result;
    }
    private static class Fixture {
        final UUID id=UUID.randomUUID();
        final AdvancedCorePlugin plugin=mock(AdvancedCorePlugin.class);
        final UserManager users=mock(UserManager.class);
        final AdvancedCoreUser user=mock(AdvancedCoreUser.class);
        final UserData data=mock(UserData.class);
        final UserDataManager manager=mock(UserDataManager.class,CALLS_REAL_METHODS);
        Fixture() {
            try {
                set("plugin",plugin);set("userDataCache",new ConcurrentHashMap<UUID,UserDataCache>());
                set("keys",new ArrayList<>(Collections.singletonList(new UserDataKeyString("Points"))));
                set("timer",mock(ScheduledExecutorService.class));
                when(plugin.getUserManager()).thenReturn(users);when(users.getUser(any(UUID.class),eq(false))).thenReturn(user);
                when(user.getUserData()).thenReturn(data);when(data.getValuesStrict()).thenAnswer(call->values("stored"));
            } catch(Exception failure){throw new AssertionError(failure);}
        }
        UserDataCache existing(){UserDataCache cache=new UserDataCache(manager,id);cache.updateCache(values("stored"));manager.getUserDataCache().put(id,cache);return cache;}
        void set(String name,Object value)throws Exception {Field field=UserDataManager.class.getDeclaredField(name);field.setAccessible(true);field.set(manager,value);}
    }
}

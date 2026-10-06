package com.bencodez.advancedcore.api.user.usercache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.user.*;
import com.bencodez.advancedcore.api.user.usercache.change.UserDataChangeString;
import com.bencodez.simpleapi.sql.data.DataValue;

class LegacyCheckedCacheFlushTest {
    @Test void rejectedWriteRetainsUndumpedPayloadAndRetryUsesNewerValue() throws Exception {
        Fixture f=new Fixture();UserDataChangeString old=spy(new UserDataChangeString("Points","1"));
        SQLException rejected=new SQLException("write rejected");
        doAnswer(call->{f.cache.addChange(new UserDataChangeString("Points","2"),true);throw rejected;})
            .doAnswer(call->{Map<String,DataValue> values=call.getArgument(0);assertEquals("2",values.get("Points").getString());return null;})
            .when(f.data).setValuesStrict(any());
        f.cache.addChange(old,true);
        IllegalStateException failure=assertThrows(IllegalStateException.class,f.cache::processChanges);
        assertSame(rejected,failure.getCause());assertTrue(f.cache.hasChangesToProcess());verify(old,never()).dump();
        verifyNoInteractions(f.users);f.cache.processChanges();verify(old).dump();
        assertFalse(f.cache.hasChangesToProcess());verify(f.data,never()).setValues(any());
    }
    @Test void notificationFailureDoesNotRequeueACommittedBatch() throws Exception {
        Fixture f=new Fixture();UserDataChangeString change=spy(new UserDataChangeString("Points","1"));
        doThrow(new IllegalStateException("listener rejected")).when(f.users).onChange(eq(f.user),any(String[].class));
        f.cache.addChange(change,true);assertThrows(IllegalStateException.class,f.cache::processChanges);
        assertFalse(f.cache.hasChangesToProcess());verify(change).dump();f.cache.processChanges();
        verify(f.data,times(1)).setValuesStrict(any());
    }
    @Test void failedRetirementKeepsIdentityAndPendingBatchAvailableForRetry() throws Exception {
        Fixture f=new Fixture();UUID identity=f.cache.getUuid();
        doThrow(new SQLException("offline")).doNothing().when(f.data).setValuesStrict(any());
        f.cache.addChange(new UserDataChangeString("Points","1"),true);
        assertThrows(IllegalStateException.class,f.cache::dump);
        assertEquals(identity,f.cache.getUuid());assertTrue(f.cache.hasChangesToProcess());
        f.cache.addChange(new UserDataChangeString("Points","2"),true);
        f.cache.dump();assertNull(f.cache.getUuid());assertFalse(f.cache.hasCache());assertFalse(f.cache.hasChangesToProcess());
    }
    @Test void retirementWaitsForClaimedWriteAndFlushesLaterAcceptedChange() throws Exception {
        Fixture f=new Fixture();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        List<String> writes=Collections.synchronizedList(new ArrayList<>());
        doAnswer(call->{Map<String,DataValue> values=call.getArgument(0);writes.add(values.get("Points").getString());
            if(writes.size()==1){entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));}return null;})
            .when(f.data).setValuesStrict(any());
        ExecutorService workers=Executors.newFixedThreadPool(2);Future<?> flush=null,retire=null;
        try {
            f.cache.addChange(new UserDataChangeString("Points","1"),true);flush=workers.submit(f.cache::processChanges);
            assertTrue(entered.await(5,TimeUnit.SECONDS));assertTrue(f.cache.hasChangesToProcess());
            f.cache.addChange(new UserDataChangeString("Points","2"),true);
            CountDownLatch retirementStarted=new CountDownLatch(1);
            retire=workers.submit(()->{retirementStarted.countDown();f.cache.dump();});assertTrue(retirementStarted.await(5,TimeUnit.SECONDS));
            Future<?> waitingRetirement=retire;assertThrows(TimeoutException.class,()->retireWait(waitingRetirement));assertNotNull(f.cache.getUuid());
            release.countDown();flush.get(5,TimeUnit.SECONDS);retire.get(5,TimeUnit.SECONDS);
            assertEquals(Arrays.asList("1","2"),writes);assertNull(f.cache.getUuid());
        } finally {release.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
    }
    private void retireWait(Future<?> future)throws Exception {future.get(100,TimeUnit.MILLISECONDS);}
    @Test void callbackCanRetireOnAnotherThreadWithoutOwningTheStorageLock() throws Exception {
        Fixture f=new Fixture();ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            doAnswer(call->{worker.submit(f.cache::dump).get(5,TimeUnit.SECONDS);return null;})
                .when(f.users).onChange(eq(f.user),any(String[].class));
            f.cache.addChange(new UserDataChangeString("Points","1"),true);assertDoesNotThrow(f.cache::processChanges);
            assertNull(f.cache.getUuid());verify(f.data).setValuesStrict(any());
        } finally {worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void retirementRejectsNewAdmissionVisiblyWhileItsFinalBatchWrites() throws Exception {
        Fixture f=new Fixture();doAnswer(call->{
            assertThrows(IllegalStateException.class,f.cache::dump);
            assertThrows(IllegalStateException.class,()->f.cache.addChange(new UserDataChangeString("Points","2"),true));
            return null;
        }).when(f.data).setValuesStrict(any());
        f.cache.addChange(new UserDataChangeString("Points","1"),true);f.cache.dump();
        assertNull(f.cache.getUuid());assertThrows(IllegalStateException.class,
            ()->f.cache.addChange(new UserDataChangeString("Points","3"),true));
    }
    @Test void preparationFailureDoesNotDiscardTheOriginalQueue() throws Exception {
        Fixture f=new Fixture();UserDataChangeString change=spy(new UserDataChangeString("Points","1"));
        f.cache.addChange(change,true);IllegalStateException rejection=new IllegalStateException("bad conversion");
        doThrow(rejection).when(change).toUserDataValue();
        assertSame(rejection,assertThrows(IllegalStateException.class,f.cache::processChanges));
        assertTrue(f.cache.hasChangesToProcess());verify(change,never()).dump();verifyNoInteractions(f.data,f.users);
        doCallRealMethod().when(change).toUserDataValue();f.cache.processChanges();assertFalse(f.cache.hasChangesToProcess());
    }
    @Test void repeatedBackgroundStorageFailuresRemainVisibleWithoutRepeatingWarnings() throws Exception {
        Fixture f=new Fixture();List<Runnable> tasks=new ArrayList<>();
        ScheduledExecutorService timer=f.manager.getTimer();
        doAnswer(call->{tasks.add(call.getArgument(0));return null;}).when(timer)
            .schedule(any(Runnable.class),eq(3L),eq(TimeUnit.SECONDS));
        doThrow(new SQLException("write rejected")).doThrow(new SQLException("still unavailable"))
            .doNothing().doThrow(new SQLException("new outage")).when(f.data).setValuesStrict(any());
        f.cache.addChange(new UserDataChangeString("Points","1"),true);
        tasks.get(0).run();tasks.get(1).run();assertTrue(f.cache.hasChangesToProcess());
        verify(f.plugin.getLogger(),times(1)).warning(contains("pending changes are retained"));
        tasks.get(2).run();assertFalse(f.cache.hasChangesToProcess());
        f.cache.addChange(new UserDataChangeString("Points","2"),true);tasks.get(3).run();
        verify(f.plugin.getLogger(),times(2)).warning(contains("pending changes are retained"));
    }
    @Test void recursiveStorageCallbackCannotRetireOrReorderItsOwnBatch() throws Exception {
        Fixture f=new Fixture();doAnswer(call->{
            assertThrows(IllegalStateException.class,f.cache::dump);
            assertThrows(IllegalStateException.class,f.cache::processChanges);
            assertNotNull(f.cache.getUuid());return null;
        }).when(f.data).setValuesStrict(any());
        f.cache.addChange(new UserDataChangeString("Points","1"),true);f.cache.processChanges();
        assertNotNull(f.cache.getUuid());assertFalse(f.cache.hasChangesToProcess());
    }
    private static class Fixture {
        final AdvancedCorePlugin plugin=mock(AdvancedCorePlugin.class);
        final UserDataManager manager=mock(UserDataManager.class);
        final UserManager users=mock(UserManager.class);
        final AdvancedCoreUser user=mock(AdvancedCoreUser.class);
        final UserData data=mock(UserData.class);
        final UserDataCache cache;
        Fixture() {
            when(plugin.getLogger()).thenReturn(mock(java.util.logging.Logger.class));
            when(manager.getPlugin()).thenReturn(plugin);when(manager.getTimer()).thenReturn(mock(ScheduledExecutorService.class));
            when(plugin.getUserManager()).thenReturn(users);when(user.getUserData()).thenReturn(data);
            cache=spy(new UserDataCache(manager,UUID.randomUUID()));doReturn(user).when(cache).getUser();
        }
    }
}

package com.bencodez.advancedcore.api.user;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.api.rewards.*;
import com.bencodez.simpleapi.sql.data.*;

class LegacyTimedQueueReplayTest {
    private String entry(String key,long time){return key+"%ExecutionTime/%"+time;}
    private String due(){return entry("daily%extime%1",System.currentTimeMillis()-1000);}
    private void fixture(List<String> pending,Consumer<LegacyOfflineQueueReplayTest.Fixture> body) {
        new LegacyOfflineQueueReplayTest().fixture(Collections.emptyList(),x->{
            HashMap<String,DataValue> values=new HashMap<>();values.put("OfflineRewards",new DataValueString(""));values.put("TimedRewards",new DataValueString(String.join("%line%",pending)));x.f.cache.updateCache(values);
            doCallRealMethod().when(x.f.user).checkDelayedTimedRewardsAsync();doCallRealMethod().when(x.f.user).checkDelayedTimedRewards();doCallRealMethod().when(x.f.user).addTimedReward(any(),any(),anyLong());body.accept(x);
        });
    }
    private String pending(LegacyOfflineQueueReplayTest.Fixture x){return x.f.cache.getCachedValue("TimedRewards").getString();}
    private Reward.ReplayCheckpoint checkpoint() {
        try {java.lang.reflect.Constructor<Reward.ReplayCheckpoint> c=Reward.ReplayCheckpoint.class.getDeclaredConstructor(Map.class,Map.class,HashMap.class);c.setAccessible(true);return c.newInstance(Collections.singletonMap("daily",1),Collections.singletonMap("daily","registry"),new HashMap<String,String>());}catch(Exception failure){throw new AssertionError(failure);}
    }
    @Test void dueOccurrenceRemainsDurableUntilEffectCompletes() {
        fixture(Collections.singletonList(due()),x->{CompletableFuture<Void> effect=new CompletableFuture<>();when(x.rewards.givePersistedQueueRewardAsync(any(),any(),any())).thenAnswer(call->{RewardOptions options=call.getArgument(2);assertNotNull(options.getAsyncReplayOccurrenceId());assertNotNull(options.getPlaceholders().get("date"));assertTrue(pending(x).contains("%asyncoccurrence%"));return effect;});CompletionStage<Void> result=x.f.user.checkDelayedTimedRewardsAsync();assertFalse(result.toCompletableFuture().isDone());effect.complete(null);result.toCompletableFuture().join();assertEquals("",pending(x));});
    }
    @Test void anotherPollCannotStartActiveTimedOccurrence() {
        fixture(Collections.singletonList(due()),x->{CompletableFuture<Void> effect=new CompletableFuture<>();when(x.rewards.givePersistedQueueRewardAsync(any(),any(),any())).thenReturn(effect);CompletionStage<Void> first=x.f.user.checkDelayedTimedRewardsAsync();x.f.user.checkDelayedTimedRewardsAsync().toCompletableFuture().join();verify(x.rewards,times(1)).givePersistedQueueRewardAsync(any(),any(),any());effect.complete(null);first.toCompletableFuture().join();});
    }
    @Test void offlineAndTimedWorkShareOneSerialOwner() {
        fixture(Collections.singletonList(due()),x->{HashMap<String,DataValue> values=new HashMap<>();values.put("OfflineRewards",new DataValueString("offline"));values.put("TimedRewards",new DataValueString(pending(x)));x.f.cache.updateCache(values);List<CompletableFuture<Void>> effects=new ArrayList<>();when(x.rewards.givePersistedQueueRewardAsync(any(),any(),any())).thenAnswer(call->{CompletableFuture<Void> effect=new CompletableFuture<>();effects.add(effect);return effect;});CompletionStage<Void> offline=x.f.user.checkOfflineRewardsAsync(),timed=x.f.user.checkDelayedTimedRewardsAsync();assertEquals(1,effects.size());effects.get(0).complete(null);assertEquals(2,effects.size());effects.get(1).complete(null);offline.toCompletableFuture().join();timed.toCompletableFuture().join();assertEquals("",pending(x));});
    }
    @Test void futureAndZeroEntriesArePreservedWithoutEffects() {
        List<String> entries=Arrays.asList(entry("future",System.currentTimeMillis()+60000),entry("zero",0));fixture(entries,x->{x.f.user.checkDelayedTimedRewardsAsync().toCompletableFuture().join();assertEquals(String.join("%line%",entries),pending(x));verify(x.rewards,never()).givePersistedQueueRewardAsync(any(),any(),any());});
    }
    @Test void failedEffectPersistsRetryAndSchedulesAnActualRetryRequest() {
        fixture(Collections.singletonList(due()),x->{CompletableFuture<Void> failed=new CompletableFuture<>();failed.completeExceptionally(new IllegalStateException("effect"));when(x.rewards.givePersistedQueueRewardAsync(any(),any(),any())).thenReturn(failed);long before=System.currentTimeMillis();assertThrows(CompletionException.class,()->x.f.user.checkDelayedTimedRewardsAsync().toCompletableFuture().join());String retained=pending(x);assertTrue(retained.contains("%asyncoccurrence%"));assertTrue(retained.contains("%asyncretry%1"));int marker=retained.lastIndexOf("%ExecutionTime/%");long next=Long.parseLong(retained.substring(marker+"%ExecutionTime/%".length()));assertTrue(next>before);verify(x.f.user).loadTimedDelayedTimer(next);});
    }
    @Test void checkpointAndCompletionCannotEraseNewTimedAppend() {
        fixture(Collections.singletonList(due()),x->{List<RewardOptions> options=new ArrayList<>();CompletableFuture<Void> effect=new CompletableFuture<>();when(x.rewards.givePersistedQueueRewardAsync(any(),any(),any())).thenAnswer(call->{options.add(call.getArgument(2));return effect;});CompletionStage<Void> result=x.f.user.checkDelayedTimedRewardsAsync();Reward added=mock(Reward.class);when(added.getRewardName()).thenReturn("later");x.f.user.addTimedReward(added,new HashMap<>(),System.currentTimeMillis()+60000);options.get(0).getAsyncReplayCheckpointConsumer().accept(checkpoint());assertTrue(pending(x).contains("%asyncprogress%v3-"));effect.complete(null);result.toCompletableFuture().join();assertFalse(pending(x).contains("daily"));assertTrue(pending(x).contains("normal/"));});
    }
    @Test void unreadableOrMalformedTimedEntryNeverBecomesACompletedReward() {
        for(String value:Arrays.asList("missing-date",entry("daily",-1),"daily%ExecutionTime/%invalid"))fixture(Collections.singletonList(value),x->{assertThrows(CompletionException.class,()->x.f.user.checkDelayedTimedRewardsAsync().toCompletableFuture().join());assertEquals(value,pending(x));verify(x.rewards,never()).givePersistedQueueRewardAsync(any(),any(),any());});
    }
    @Test void sameRewardAndDueDateProduceDistinctPersistedOccurrences() {
        fixture(Collections.emptyList(),x->{Reward reward=mock(Reward.class);when(reward.getRewardName()).thenReturn("daily");long due=System.currentTimeMillis()+60000;x.f.user.addTimedReward(reward,new HashMap<>(),due);x.f.user.addTimedReward(reward,new HashMap<>(),due);String[] entries=pending(x).split("%line%");assertEquals(2,entries.length);assertNotEquals(entries[0],entries[1]);verify(x.f.user,times(2)).loadTimedDelayedTimer(due);});
    }
    @Test void checkpointStorageFailureRetainsItsLastAcknowledgedEntry() {
        fixture(Collections.singletonList(due()),x->{List<RewardOptions> options=new ArrayList<>();CompletableFuture<Void> effect=new CompletableFuture<>();when(x.rewards.givePersistedQueueRewardAsync(any(),any(),any())).thenAnswer(call->{options.add(call.getArgument(2));return effect;});CompletionStage<Void> result=x.f.user.checkDelayedTimedRewardsAsync();String admitted=pending(x);try{doThrow(new SQLException("checkpoint")).when(x.f.mysql).updateStrict(anyString(),anyList());}catch(SQLException failure){throw new AssertionError(failure);}IllegalStateException rejected=assertThrows(IllegalStateException.class,()->options.get(0).getAsyncReplayCheckpointConsumer().accept(checkpoint()));assertEquals(admitted,pending(x));effect.completeExceptionally(rejected);assertThrows(CompletionException.class,()->result.toCompletableFuture().join());assertEquals(admitted,pending(x));});
    }
    @Test void committedNotificationFailureDoesNotDuplicateTheTimedOccurrence() {
        fixture(Collections.singletonList(due()),x->{doThrow(new IllegalStateException("notification")).when(x.f.users).onChange(eq(x.f.user),any(String[].class));when(x.rewards.givePersistedQueueRewardAsync(any(),any(),any())).thenReturn(CompletableFuture.completedFuture(null));x.f.user.checkDelayedTimedRewardsAsync().toCompletableFuture().join();assertEquals("",pending(x));verify(x.rewards,times(1)).givePersistedQueueRewardAsync(any(),any(),any());});
    }
    @Test void aColdStorageReadFailureCannotBecomeAnEmptyTimedQueue() {
        fixture(Collections.emptyList(),x->{HashMap<String,DataValue> values=new HashMap<>();values.put("OfflineRewards",new DataValueString(""));x.f.cache.updateCache(values);try{when(x.f.mysql.getExactStrict(anyString())).thenThrow(new SQLException("read"));}catch(SQLException failure){throw new AssertionError(failure);}assertThrows(CompletionException.class,()->x.f.user.checkDelayedTimedRewardsAsync().toCompletableFuture().join());assertNull(x.f.cache.getCachedValue("TimedRewards"));verify(x.rewards,never()).givePersistedQueueRewardAsync(any(),any(),any());});
    }

    @Test void executionAndRetryMarkersInsidePlaceholdersRemainData() {
        HashMap<String,String> placeholders=new HashMap<>();String note="%extime%123 %asyncretry%bad %ExecutionTime/%456";placeholders.put("note",note);
        String original=entry("daily%extime%1%placeholders%"+com.bencodez.simpleapi.array.ArrayUtils.makeString(placeholders),System.currentTimeMillis()-1000);
        fixture(Collections.singletonList(original),x->{
            when(x.rewards.givePersistedQueueRewardAsync(any(),any(),any())).thenAnswer(call->{RewardOptions options=call.getArgument(2);assertEquals(note,options.getPlaceholders().get("note"));CompletableFuture<Void> failed=new CompletableFuture<>();failed.completeExceptionally(new IllegalStateException("effect"));return failed;});
            assertThrows(CompletionException.class,()->x.f.user.checkDelayedTimedRewardsAsync().toCompletableFuture().join());assertTrue(pending(x).contains(note));assertTrue(pending(x).contains("%asyncretry%1%placeholders%"));
        });
    }
    @Test void duplicateLegacyTimedKeyFailsBeforeAnyReward() {
        String record=due();fixture(Arrays.asList(record,record),x->{assertThrows(CompletionException.class,()->x.f.user.checkDelayedTimedRewardsAsync().toCompletableFuture().join());assertEquals(record+"%line%"+record,pending(x));verify(x.rewards,never()).givePersistedQueueRewardAsync(any(),any(),any());});
    }

    @Test void repairedStorageGetsAnotherTimerWakeupAfterFailedAdmission() {
        String original=due();fixture(Collections.singletonList(original),x->{
            List<Runnable> scheduled=new ArrayList<>();
            ScheduledExecutorService timer=mock(ScheduledExecutorService.class);
            ScheduledFuture<?> handle=mock(ScheduledFuture.class);
            when(x.rewards.getDelayedTimer()).thenReturn(timer);
            when(timer.schedule(any(Runnable.class),anyLong(),eq(TimeUnit.MILLISECONDS))).thenAnswer(call->{scheduled.add(call.getArgument(0));return handle;});
            doCallRealMethod().when(x.f.user).loadTimedDelayedTimer(anyLong());
            when(x.rewards.givePersistedQueueRewardAsync(any(),any(),any())).thenReturn(CompletableFuture.completedFuture(null));
            try {doThrow(new SQLException("temporary storage outage")).when(x.f.mysql).updateStrict(anyString(),anyList());}catch(SQLException failure){throw new AssertionError(failure);}
            x.f.user.loadTimedDelayedTimer(System.currentTimeMillis()-1000);
            assertEquals(1,scheduled.size());scheduled.remove(0).run();
            assertEquals(original,pending(x));verify(x.rewards,never()).givePersistedQueueRewardAsync(any(),any(),any());
            try {doNothing().when(x.f.mysql).updateStrict(anyString(),anyList());}catch(SQLException failure){throw new AssertionError(failure);}
            assertEquals(1,scheduled.size(),"A temporary storage failure must retain one bounded timer wakeup for repaired storage");
            scheduled.remove(0).run();
            assertEquals("",pending(x));verify(x.rewards,times(1)).givePersistedQueueRewardAsync(any(),any(),any());
        });
    }

    private List<Runnable> recoveryTimer(LegacyOfflineQueueReplayTest.Fixture x,ScheduledFuture<?> handle) {
        List<Runnable> scheduled=new ArrayList<>();ScheduledExecutorService timer=mock(ScheduledExecutorService.class);
        when(x.rewards.getDelayedTimer()).thenReturn(timer);
        when(timer.schedule(any(Runnable.class),anyLong(),eq(TimeUnit.MILLISECONDS))).thenAnswer(call->{scheduled.add(call.getArgument(0));return handle;});
        return scheduled;
    }
    @Test void repeatedStorageFailurePollsShareOneWakeup() {
        fixture(Collections.singletonList(due()),x->{
            List<Runnable> scheduled=recoveryTimer(x,mock(ScheduledFuture.class));
            try{doThrow(new SQLException("outage")).when(x.f.mysql).updateStrict(anyString(),anyList());}catch(SQLException failure){throw new AssertionError(failure);}
            for(int i=0;i<3;i++)assertThrows(CompletionException.class,()->x.f.user.checkDelayedTimedRewardsAsync().toCompletableFuture().join());
            assertEquals(1,scheduled.size());scheduled.remove(0).run();assertEquals(1,scheduled.size());
            AdvancedCoreUser.cancelTimedStorageWakeups(x.f.plugin);
        });
    }
    @Test void coldReadOutageCanRecoverThroughTheSameTimer() {
        fixture(Collections.emptyList(),x->{
            List<Runnable> scheduled=recoveryTimer(x,mock(ScheduledFuture.class));
            HashMap<String,DataValue> values=new HashMap<>();values.put("OfflineRewards",new DataValueString(""));x.f.cache.updateCache(values);
            try{when(x.f.mysql.getExactStrict(anyString())).thenThrow(new SQLException("cold read"));}catch(SQLException failure){throw new AssertionError(failure);}
            assertThrows(CompletionException.class,()->x.f.user.checkDelayedTimedRewardsAsync().toCompletableFuture().join());assertEquals(1,scheduled.size());
            values.put("TimedRewards",new DataValueString(due()));x.f.cache.updateCache(values);
            when(x.rewards.givePersistedQueueRewardAsync(any(),any(),any())).thenReturn(CompletableFuture.completedFuture(null));
            scheduled.remove(0).run();assertEquals("",pending(x));verify(x.rewards,times(1)).givePersistedQueueRewardAsync(any(),any(),any());
        });
    }
    @Test void malformedMetadataDoesNotCreateARepeatedStorageWakeup() {
        fixture(Collections.singletonList("missing-date"),x->{List<Runnable> scheduled=recoveryTimer(x,mock(ScheduledFuture.class));assertThrows(CompletionException.class,()->x.f.user.checkDelayedTimedRewardsAsync().toCompletableFuture().join());assertTrue(scheduled.isEmpty());});
    }
    @Test void storageWakeupIsCancelledAndCannotRunAfterRetirement() {
        fixture(Collections.singletonList(due()),x->{
            ScheduledFuture<?> handle=mock(ScheduledFuture.class);List<Runnable> scheduled=recoveryTimer(x,handle);
            try{doThrow(new SQLException("outage")).when(x.f.mysql).updateStrict(anyString(),anyList());}catch(SQLException failure){throw new AssertionError(failure);}
            assertThrows(CompletionException.class,()->x.f.user.checkDelayedTimedRewardsAsync().toCompletableFuture().join());
            AdvancedCoreUser.cancelTimedStorageWakeups(x.f.plugin);verify(handle).cancel(false);
            scheduled.remove(0).run();verify(x.rewards,never()).givePersistedQueueRewardAsync(any(),any(),any());
        });
    }
    @Test void retiredRuntimeWakeupDoesNotUseTheReplacementDispatcher() {
        fixture(Collections.singletonList(due()),x->{
            List<Runnable> scheduled=recoveryTimer(x,mock(ScheduledFuture.class));
            try{doThrow(new SQLException("outage")).when(x.f.mysql).updateStrict(anyString(),anyList());}catch(SQLException failure){throw new AssertionError(failure);}
            assertThrows(CompletionException.class,()->x.f.user.checkDelayedTimedRewardsAsync().toCompletableFuture().join());
            when(x.f.plugin.getRewardDispatch()).thenReturn(new ServerThreadRewardDispatch(x.f.plugin));
            scheduled.remove(0).run();assertTrue(scheduled.isEmpty());verify(x.rewards,never()).givePersistedQueueRewardAsync(any(),any(),any());
        });
    }

    @Test void admissionFailureDoesNotStartEffectsOrLoseItsPredecessor() {
        String original=due();fixture(Collections.singletonList(original),x->{try {doThrow(new SQLException("storage")).when(x.f.mysql).updateStrict(anyString(),anyList());}catch(SQLException failure){throw new AssertionError(failure);}assertThrows(CompletionException.class,()->x.f.user.checkDelayedTimedRewardsAsync().toCompletableFuture().join());assertEquals(original,pending(x));verify(x.rewards,never()).givePersistedQueueRewardAsync(any(),any(),any());});
    }
}

package com.bencodez.advancedcore.api.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import com.bencodez.simpleapi.sql.data.*;
import com.bencodez.advancedcore.api.rewards.*;

class LegacyQueuePublicationRecoveryTest {
    private void await(CompletionStage<Void> stage) {
        try{stage.toCompletableFuture().get(2,TimeUnit.SECONDS);}
        catch(ExecutionException failure){throw new CompletionException(failure.getCause());}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}
        catch(TimeoutException failure){throw new AssertionError("Queue publication did not settle within the test deadline",failure);}
    }
    private void fixture(boolean timed,Consumer<LegacyOfflineQueueReplayTest.Fixture> body) {
        new LegacyOfflineQueueReplayTest().fixture(timed?Collections.emptyList():Collections.singletonList("daily"),x->{
            HashMap<String,DataValue> values=new HashMap<>();values.put("OfflineRewards",new DataValueString(timed?"":"daily"));
            values.put("TimedRewards",new DataValueString(timed?"daily%extime%1%ExecutionTime/%"+(System.currentTimeMillis()-1000):""));x.f.cache.updateCache(values);
            doCallRealMethod().when(x.f.user).checkDelayedTimedRewardsAsync();body.accept(x);
        });
    }
    private String pending(LegacyOfflineQueueReplayTest.Fixture x,boolean timed){return x.f.cache.getCachedValue(timed?"TimedRewards":"OfflineRewards").getString();}
    private void completionRecovery(boolean timed) {
        fixture(timed,x->{
            List<Runnable> scheduled=new ArrayList<>();ScheduledExecutorService timer=mock(ScheduledExecutorService.class);
            when(x.rewards.getDelayedTimer()).thenReturn(timer);
            when(timer.schedule(any(Runnable.class),anyLong(),eq(TimeUnit.MILLISECONDS))).thenAnswer(call->{scheduled.add(call.getArgument(0));return mock(ScheduledFuture.class);});
            CompletableFuture<Void> effect=new CompletableFuture<>();when(x.rewards.givePersistedQueueRewardAsync(any(),any(),any())).thenReturn(effect);
            CompletionStage<Void> result=timed?x.f.user.checkDelayedTimedRewardsAsync():x.f.user.checkOfflineRewardsAsync();String admitted=pending(x,timed);
            try{doThrow(new SQLException("completion outage")).when(x.f.mysql).updateStrict(anyString(),anyList());}catch(SQLException failure){throw new AssertionError(failure);}
            effect.complete(null);
            assertFalse(result.toCompletableFuture().isDone(),"Completion must await the retained publication rather than release the occurrence");
            assertEquals(admitted,pending(x,timed));assertEquals(1,scheduled.size());
            scheduled.remove(0).run();assertEquals(1,scheduled.size());assertFalse(result.toCompletableFuture().isDone());
            await(timed?x.f.user.checkDelayedTimedRewardsAsync():x.f.user.checkOfflineRewardsAsync());
            verify(x.rewards,times(1)).givePersistedQueueRewardAsync(any(),any(),any());
            try{doNothing().when(x.f.mysql).updateStrict(anyString(),anyList());}catch(SQLException failure){throw new AssertionError(failure);}
            scheduled.remove(0).run();await(result);assertEquals("",pending(x,timed));
            verify(x.rewards,times(1)).givePersistedQueueRewardAsync(any(),any(),any());
        });
    }
    private List<Runnable> timer(LegacyOfflineQueueReplayTest.Fixture x,ScheduledFuture<?> handle) {
        List<Runnable> jobs=new ArrayList<>();ScheduledExecutorService timer=mock(ScheduledExecutorService.class);when(x.rewards.getDelayedTimer()).thenReturn(timer);
        when(timer.schedule(any(Runnable.class),anyLong(),eq(TimeUnit.MILLISECONDS))).thenAnswer(call->{jobs.add(call.getArgument(0));return handle;});return jobs;
    }
    private void outage(LegacyOfflineQueueReplayTest.Fixture x,boolean failed) {
        try {if(failed)doThrow(new SQLException("outage")).when(x.f.mysql).updateStrict(anyString(),anyList());else doNothing().when(x.f.mysql).updateStrict(anyString(),anyList());}catch(SQLException failure){throw new AssertionError(failure);}
    }
    private RuntimeException replayFailure() {
        try {
            RewardOptions options=new RewardOptions();options.setAsyncReplayProgress(Collections.singletonMap("daily",1));options.setAsyncReplayRegistryFingerprints(Collections.singletonMap("daily","registry"));
            java.lang.reflect.Constructor<Reward.RewardReplayFailure> c=Reward.RewardReplayFailure.class.getDeclaredConstructor(Reward.ReplayState.class,HashMap.class,Throwable.class);c.setAccessible(true);
            return c.newInstance(Reward.replayStateFor(options),new HashMap<String,String>(),new IllegalStateException("checkpoint failed",new SQLException("checkpoint outage")));
        }catch(Exception failure){throw new AssertionError(failure);}
    }
    private void progressRecovery(boolean timed) {
        fixture(timed,x->{
            List<Runnable> jobs=timer(x,mock(ScheduledFuture.class));CompletableFuture<Void> effect=new CompletableFuture<>();when(x.rewards.givePersistedQueueRewardAsync(any(),any(),any())).thenReturn(effect);
            CompletionStage<Void> result=timed?x.f.user.checkDelayedTimedRewardsAsync():x.f.user.checkOfflineRewardsAsync();String before=pending(x,timed);outage(x,true);effect.completeExceptionally(replayFailure());
            assertFalse(result.toCompletableFuture().isDone());assertEquals(before,pending(x,timed));assertEquals(1,jobs.size());
            await(timed?x.f.user.checkDelayedTimedRewardsAsync():x.f.user.checkOfflineRewardsAsync());verify(x.rewards,times(1)).givePersistedQueueRewardAsync(any(),any(),any());
            outage(x,false);jobs.remove(0).run();assertThrows(CompletionException.class,()->await(result));
            assertTrue(pending(x,timed).contains("%asyncprogress%v3-"));assertTrue(pending(x,timed).contains("%asyncoccurrence%"));verify(x.rewards,times(1)).givePersistedQueueRewardAsync(any(),any(),any());
        });
    }
    @Test void offlineRecoveryMetadataIsRetainedUntilAcknowledged(){progressRecovery(false);}
    @Test void timedRecoveryMetadataIsRetainedUntilAcknowledged(){progressRecovery(true);}
    @Test void completionRepairPreservesANewQueueAppend() {
        fixture(false,x->{
            List<Runnable> jobs=timer(x,mock(ScheduledFuture.class));CompletableFuture<Void> effect=new CompletableFuture<>();when(x.rewards.givePersistedQueueRewardAsync(any(),any(),any())).thenReturn(effect);
            CompletionStage<Void> result=x.f.user.checkOfflineRewardsAsync();outage(x,true);effect.complete(null);outage(x,false);
            Reward extra=mock(Reward.class);when(extra.getRewardName()).thenReturn("extra");x.f.user.addOfflineRewards(extra,new HashMap<>());
            jobs.remove(0).run();await(result);assertFalse(pending(x,false).contains("daily"));assertTrue(pending(x,false).contains("normal/"));verify(x.rewards,times(1)).givePersistedQueueRewardAsync(any(),any(),any());
        });
    }
    @Test void offlinePublicationKeepsTimedWorkBehindItsSerialTail() {
        fixture(false,x->{
            HashMap<String,DataValue> values=new HashMap<>();values.put("OfflineRewards",new DataValueString("daily"));values.put("TimedRewards",new DataValueString("timed%extime%1%ExecutionTime/%"+(System.currentTimeMillis()-1000)));x.f.cache.updateCache(values);
            List<Runnable> jobs=timer(x,mock(ScheduledFuture.class));List<CompletableFuture<Void>> effects=new ArrayList<>();when(x.rewards.givePersistedQueueRewardAsync(any(),any(),any())).thenAnswer(call->{CompletableFuture<Void> effect=new CompletableFuture<>();effects.add(effect);return effect;});
            CompletionStage<Void> offline=x.f.user.checkOfflineRewardsAsync();outage(x,true);effects.get(0).complete(null);CompletionStage<Void> timed=x.f.user.checkDelayedTimedRewardsAsync();assertEquals(1,effects.size());
            outage(x,false);jobs.remove(0).run();assertEquals(2,effects.size());effects.get(1).complete(null);await(offline);await(timed);assertEquals("",pending(x,false));assertEquals("",pending(x,true));
        });
    }
    @Test void retirementCancelsTheRetryAndFencesAnAlreadyQueuedCallback() {
        fixture(false,x->{
            ScheduledFuture<?> handle=mock(ScheduledFuture.class);List<Runnable> jobs=timer(x,handle);CompletableFuture<Void> effect=new CompletableFuture<>();when(x.rewards.givePersistedQueueRewardAsync(any(),any(),any())).thenReturn(effect);
            CompletionStage<Void> result=x.f.user.checkOfflineRewardsAsync();String before=pending(x,false);outage(x,true);effect.complete(null);
            AdvancedCoreUser.retireQueuePublications(x.f.plugin);verify(handle).cancel(false);assertThrows(CompletionException.class,()->await(result));
            outage(x,false);jobs.remove(0).run();assertEquals(before,pending(x,false));verify(x.rewards,times(1)).givePersistedQueueRewardAsync(any(),any(),any());
        });
    }
    @Test void retirementDoesNotReleaseAnAdmittedPhysicalCommit() {
        fixture(false,x->{
            CompletableFuture<Void> effect=new CompletableFuture<>();when(x.rewards.givePersistedQueueRewardAsync(any(),any(),any())).thenReturn(effect);CompletionStage<Void> result=x.f.user.checkOfflineRewardsAsync();
            try{doAnswer(call->{AdvancedCoreUser.retireQueuePublications(x.f.plugin);assertFalse(result.toCompletableFuture().isDone());return null;}).when(x.f.mysql).updateStrict(anyString(),anyList());}catch(SQLException failure){throw new AssertionError(failure);}
            effect.complete(null);await(result);assertEquals("",pending(x,false));verify(x.rewards,times(1)).givePersistedQueueRewardAsync(any(),any(),any());
        });
    }
    @Test void retirementDuringFuturePublicationCancelsTheLateFuture() {
        fixture(false,x->{
            ScheduledFuture<?> handle=mock(ScheduledFuture.class);ScheduledExecutorService timer=mock(ScheduledExecutorService.class);when(x.rewards.getDelayedTimer()).thenReturn(timer);
            when(timer.schedule(any(Runnable.class),anyLong(),eq(TimeUnit.MILLISECONDS))).thenAnswer(call->{AdvancedCoreUser.retireQueuePublications(x.f.plugin);return handle;});
            CompletableFuture<Void> effect=new CompletableFuture<>();when(x.rewards.givePersistedQueueRewardAsync(any(),any(),any())).thenReturn(effect);CompletionStage<Void> result=x.f.user.checkOfflineRewardsAsync();outage(x,true);effect.complete(null);
            assertThrows(CompletionException.class,()->await(result));verify(handle).cancel(false);verify(x.rewards,times(1)).givePersistedQueueRewardAsync(any(),any(),any());
        });
    }
    @Test void missingEntryIsAPermanentFailureAndDoesNotSpinStorageRetries() {
        fixture(false,x->{
            List<Runnable> jobs=timer(x,mock(ScheduledFuture.class));CompletableFuture<Void> effect=new CompletableFuture<>();when(x.rewards.givePersistedQueueRewardAsync(any(),any(),any())).thenReturn(effect);CompletionStage<Void> result=x.f.user.checkOfflineRewardsAsync();
            HashMap<String,DataValue> values=new HashMap<>();values.put("OfflineRewards",new DataValueString(""));values.put("TimedRewards",new DataValueString(""));x.f.cache.updateCache(values);effect.complete(null);
            assertThrows(CompletionException.class,()->await(result));assertTrue(jobs.isEmpty());
        });
    }

    @Test void completedOfflineEffectRetriesOnlyItsRemovalPublication(){completionRecovery(false);}
    @Test void completedTimedEffectRetriesOnlyItsRemovalPublication(){completionRecovery(true);}
}

package com.bencodez.advancedcore.api.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.bukkit.Bukkit;
import org.mockito.MockedStatic;
import com.bencodez.advancedcore.AdvancedCoreConfigOptions;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.rewards.*;
import com.bencodez.simpleapi.sql.data.*;

class LegacyOfflineQueueReplayTest {
    void fixture(List<String> pending,Consumer<Fixture> body) {
        LegacyDirectUserDataTest.Fixture f=new LegacyDirectUserDataTest.Fixture();
        doCallRealMethod().when(f.manager).mutateDirect(any(),anyString(),any(),any(),any());
        AdvancedCoreConfigOptions options=mock(AdvancedCoreConfigOptions.class);when(options.isProcessRewards()).thenReturn(true);when(f.plugin.getOptions()).thenReturn(options);when(f.plugin.isEnabled()).thenReturn(true);when(f.plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());when(f.users.getOfflineRewardsPath()).thenReturn("OfflineRewards");
        try(MockedStatic<AdvancedCorePlugin> global=mockStatic(AdvancedCorePlugin.class);MockedStatic<Bukkit> bukkit=mockStatic(Bukkit.class)) {
        global.when(AdvancedCorePlugin::getInstance).thenReturn(f.plugin);bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
        RewardHandler rewards=mock(RewardHandler.class);when(f.plugin.getRewardHandler()).thenReturn(rewards);
        ServerThreadRewardDispatch owner=new ServerThreadRewardDispatch(f.plugin);when(f.plugin.getRewardDispatch()).thenReturn(owner);
        try {java.lang.reflect.Field plugin=AdvancedCoreUser.class.getDeclaredField("plugin");plugin.setAccessible(true);plugin.set(f.user,f.plugin);}catch(Exception failure){throw new AssertionError(failure);}
        doCallRealMethod().when(f.user).checkOfflineRewardsAsync();doCallRealMethod().when(f.user).checkOfflineRewards();doCallRealMethod().when(f.user).forceRunOfflineRewards();doCallRealMethod().when(f.user).addOfflineRewards(any(),any());
        HashMap<String,DataValue> values=new HashMap<>();values.put("OfflineRewards",new DataValueString(String.join("%line%",pending)));f.cache.updateCache(values);
        try {body.accept(new Fixture(f,rewards,options));}finally{owner.close();RewardHandler.getInstance().getRepeatTimer().cancel();}
        }
    }
    static class Fixture {
        final LegacyDirectUserDataTest.Fixture f;final RewardHandler rewards;final AdvancedCoreConfigOptions options;
        Fixture(LegacyDirectUserDataTest.Fixture f,RewardHandler rewards,AdvancedCoreConfigOptions options){this.f=f;this.rewards=rewards;this.options=options;}
        String pending(){return f.cache.getCachedValue("OfflineRewards").getString();}
    }
    @Test void admissionIsDurableAndPendingUntilEffectsSettle() {
        fixture(Collections.singletonList("daily"),x->{
            CompletableFuture<Void> effect=new CompletableFuture<>();List<RewardOptions> options=new ArrayList<>();
            when(x.rewards.givePersistedQueueRewardAsync(eq(x.f.user),any(),any())).thenAnswer(call->{options.add(call.getArgument(2));assertTrue(x.pending().contains("%asyncoccurrence%"));return effect;});
            CompletionStage<Void> result=x.f.user.checkOfflineRewardsAsync();assertFalse(result.toCompletableFuture().isDone());assertEquals(1,options.size());assertNotNull(options.get(0).getAsyncReplayOccurrenceId());
            effect.complete(null);result.toCompletableFuture().join();assertEquals("",x.pending());
        });
    }
    @Test void secondPollCannotStartTheSameActiveOccurrence() {
        fixture(Collections.singletonList("daily"),x->{
            CompletableFuture<Void> effect=new CompletableFuture<>();when(x.rewards.givePersistedQueueRewardAsync(any(),any(),any())).thenReturn(effect);
            CompletionStage<Void> first=x.f.user.checkOfflineRewardsAsync();x.f.user.checkOfflineRewardsAsync().toCompletableFuture().join();verify(x.rewards,times(1)).givePersistedQueueRewardAsync(any(),any(),any());effect.complete(null);first.toCompletableFuture().join();
        });
    }
    @Test void separateUserWrapperCannotTakeTheSameActiveOccurrence() {
        fixture(Collections.singletonList("daily"),x->{
            CompletableFuture<Void> effect=new CompletableFuture<>();when(x.rewards.givePersistedQueueRewardAsync(any(),any(),any())).thenReturn(effect);
            CompletionStage<Void> first=x.f.user.checkOfflineRewardsAsync();AdvancedCoreUser other=mock(AdvancedCoreUser.class);String identity=x.f.user.getUUID();when(other.getUUID()).thenReturn(identity);when(other.getUserData()).thenReturn(x.f.data);doCallRealMethod().when(other).checkOfflineRewardsAsync();
            try {java.lang.reflect.Field plugin=AdvancedCoreUser.class.getDeclaredField("plugin");plugin.setAccessible(true);plugin.set(other,x.f.plugin);}catch(Exception failure){throw new AssertionError(failure);}
            other.checkOfflineRewardsAsync().toCompletableFuture().join();verify(x.rewards,times(1)).givePersistedQueueRewardAsync(any(),any(),any());effect.complete(null);first.toCompletableFuture().join();
        });
    }
    @Test void forcedVoidWrapperAlsoKeepsPendingEntryUntilCompletion() {
        fixture(Collections.singletonList("daily"),x->{CompletableFuture<Void> effect=new CompletableFuture<>();when(x.rewards.givePersistedQueueRewardAsync(any(),any(),any())).thenReturn(effect);x.f.user.forceRunOfflineRewards();assertTrue(x.pending().contains("%asyncoccurrence%"));effect.complete(null);assertEquals("",x.pending());});
    }

    @Test void identicalLegacyOccurrencesRemainDistinctAndExecuteSerially() {
        fixture(Arrays.asList("daily","daily"),x->{
            List<CompletableFuture<Void>> effects=new ArrayList<>();List<String> ids=new ArrayList<>();when(x.rewards.givePersistedQueueRewardAsync(any(),any(),any())).thenAnswer(call->{RewardOptions options=call.getArgument(2);ids.add(options.getAsyncReplayOccurrenceId());CompletableFuture<Void> effect=new CompletableFuture<>();effects.add(effect);return effect;});
            CompletionStage<Void> result=x.f.user.checkOfflineRewardsAsync();assertEquals(1,effects.size());effects.get(0).complete(null);assertEquals(2,effects.size());assertNotEquals(ids.get(0),ids.get(1));effects.get(1).complete(null);result.toCompletableFuture().join();assertEquals("",x.pending());
        });
    }
    @Test void failedAdmissionNeverStartsEffectsOrErasesLegacyEntry() {
        fixture(Collections.singletonList("daily"),x->{
            try {doThrow(new SQLException("offline")).when(x.f.mysql).updateStrict(anyString(),anyList());}catch(SQLException failure){throw new AssertionError(failure);}
            assertThrows(CompletionException.class,()->x.f.user.checkOfflineRewardsAsync().toCompletableFuture().join());assertEquals("daily",x.pending());verify(x.rewards,never()).givePersistedQueueRewardAsync(any(),any(),any());
        });
    }
    @Test void failedEffectRetainsTheSameOccurrenceForRetry() {
        fixture(Collections.singletonList("daily"),x->{
            CompletableFuture<Void> failed=new CompletableFuture<>();failed.completeExceptionally(new IllegalStateException("effect"));when(x.rewards.givePersistedQueueRewardAsync(any(),any(),any())).thenReturn(failed);
            assertThrows(CompletionException.class,()->x.f.user.checkOfflineRewardsAsync().toCompletableFuture().join());String retained=x.pending();assertTrue(retained.contains("%asyncoccurrence%"));
            when(x.rewards.givePersistedQueueRewardAsync(any(),any(),any())).thenAnswer(call->{assertEquals(retained,x.pending());return CompletableFuture.completedFuture(null);});x.f.user.checkOfflineRewardsAsync().toCompletableFuture().join();assertEquals("",x.pending());
        });
    }
    private Reward.ReplayCheckpoint checkpoint() {
        try {java.lang.reflect.Constructor<Reward.ReplayCheckpoint> c=Reward.ReplayCheckpoint.class.getDeclaredConstructor(Map.class,Map.class,HashMap.class);c.setAccessible(true);return c.newInstance(Collections.singletonMap("daily",1),Collections.singletonMap("daily","registry"),new HashMap<String,String>());}
        catch(Exception failure){throw new AssertionError(failure);}
    }
    @Test void checkpointAndCompletionPreserveAnAppendAndRetryMetadata() {
        fixture(Collections.singletonList("daily"),x->{
            CompletableFuture<Void> effect=new CompletableFuture<>();List<RewardOptions> options=new ArrayList<>();when(x.rewards.givePersistedQueueRewardAsync(any(),any(),any())).thenAnswer(call->{options.add(call.getArgument(2));return effect;});
            CompletionStage<Void> result=x.f.user.checkOfflineRewardsAsync();Reward appended=mock(Reward.class);when(appended.getRewardName()).thenReturn("another");x.f.user.addOfflineRewards(appended,new HashMap<>());
            options.get(0).getAsyncReplayCheckpointConsumer().accept(checkpoint());assertTrue(x.pending().contains("%asyncprogress%v3-"));assertTrue(x.pending().contains("normal/"));
            effect.completeExceptionally(new IllegalStateException("remaining stage"));assertThrows(CompletionException.class,()->result.toCompletableFuture().join());
            when(x.rewards.givePersistedQueueRewardAsync(any(),any(),any())).thenAnswer(call->{RewardOptions resumed=call.getArgument(2);if(resumed.getAsyncReplayOccurrenceId().equals(options.get(0).getAsyncReplayOccurrenceId())) {assertEquals(1,resumed.getAsyncReplayProgress().get("daily"));assertEquals("registry",resumed.getAsyncReplayRegistryFingerprints().get("daily"));}return CompletableFuture.completedFuture(null);});
            x.f.user.checkOfflineRewardsAsync().toCompletableFuture().join();assertEquals("",x.pending());
        });
    }
    @Test void failedCheckpointRetainsItsLastAcknowledgedPredecessor() {
        fixture(Collections.singletonList("daily"),x->{
            CompletableFuture<Void> effect=new CompletableFuture<>();List<RewardOptions> options=new ArrayList<>();when(x.rewards.givePersistedQueueRewardAsync(any(),any(),any())).thenAnswer(call->{options.add(call.getArgument(2));return effect;});CompletionStage<Void> result=x.f.user.checkOfflineRewardsAsync();String admitted=x.pending();
            try {doThrow(new SQLException("checkpoint")).when(x.f.mysql).updateStrict(anyString(),anyList());}catch(SQLException failure){throw new AssertionError(failure);}
            IllegalStateException rejected=assertThrows(IllegalStateException.class,()->options.get(0).getAsyncReplayCheckpointConsumer().accept(checkpoint()));assertEquals(admitted,x.pending());effect.completeExceptionally(rejected);assertThrows(CompletionException.class,()->result.toCompletableFuture().join());assertEquals(admitted,x.pending());
        });
    }
    @Test void failedCompletionRetainsAcknowledgedProgressForAnotherAttempt() {
        fixture(Collections.singletonList("daily"),x->{
            CompletableFuture<Void> effect=new CompletableFuture<>();List<RewardOptions> options=new ArrayList<>();when(x.rewards.givePersistedQueueRewardAsync(any(),any(),any())).thenAnswer(call->{options.add(call.getArgument(2));return effect;});CompletionStage<Void> result=x.f.user.checkOfflineRewardsAsync();options.get(0).getAsyncReplayCheckpointConsumer().accept(checkpoint());String acknowledged=x.pending();
            try {doThrow(new SQLException("delete")).when(x.f.mysql).updateStrict(anyString(),anyList());}catch(SQLException failure){throw new AssertionError(failure);}
            effect.complete(null);assertThrows(CompletionException.class,()->result.toCompletableFuture().join());assertEquals(acknowledged,x.pending());
            try {doNothing().when(x.f.mysql).updateStrict(anyString(),anyList());}catch(SQLException failure){throw new AssertionError(failure);}
            when(x.rewards.givePersistedQueueRewardAsync(any(),any(),any())).thenAnswer(call->{RewardOptions resumed=call.getArgument(2);assertEquals(1,resumed.getAsyncReplayProgress().get("daily"));return CompletableFuture.completedFuture(null);});x.f.user.checkOfflineRewardsAsync().toCompletableFuture().join();assertEquals("",x.pending());
        });
    }
    @Test void committedNotificationFailuresDoNotReplayAdmissionsOrDeletes() {
        fixture(Collections.singletonList("daily"),x->{doThrow(new IllegalStateException("notification")).when(x.f.users).onChange(eq(x.f.user),any(String[].class));when(x.rewards.givePersistedQueueRewardAsync(any(),any(),any())).thenReturn(CompletableFuture.completedFuture(null));x.f.user.checkOfflineRewardsAsync().toCompletableFuture().join();assertEquals("",x.pending());verify(x.rewards,times(1)).givePersistedQueueRewardAsync(any(),any(),any());});
    }

    @Test void malformedOccurrenceCannotBeReclassifiedAsFreshWork() {
        fixture(Collections.singletonList("daily%asyncoccurrence%invalid"),x->{assertThrows(CompletionException.class,()->x.f.user.checkOfflineRewardsAsync().toCompletableFuture().join());assertEquals("daily%asyncoccurrence%invalid",x.pending());verify(x.rewards,never()).givePersistedQueueRewardAsync(any(),any(),any());});
    }
    @Test void duplicateStoredOccurrenceIsRejectedBeforeAnyEffect() {
        String entry="daily%asyncoccurrence%"+UUID.randomUUID();fixture(Arrays.asList(entry,entry),x->{assertThrows(CompletionException.class,()->x.f.user.checkOfflineRewardsAsync().toCompletableFuture().join());verify(x.rewards,never()).givePersistedQueueRewardAsync(any(),any(),any());assertEquals(entry+"%line%"+entry,x.pending());});
    }
    @Test void malformedProgressCannotBecomeAZeroProgressReplay() {
        for(String progress:Arrays.asList("-1","v3-!","v2-!"))fixture(Collections.singletonList("daily%asyncprogress%"+progress),x->{assertThrows(CompletionException.class,()->x.f.user.checkOfflineRewardsAsync().toCompletableFuture().join());verify(x.rewards,never()).givePersistedQueueRewardAsync(any(),any(),any());assertTrue(x.pending().contains("%asyncprogress%"+progress));});
    }
    @Test void runtimeReplacementDuringAdmissionDoesNotRebindOccurrence() {
        fixture(Collections.singletonList("daily"),x->{ServerThreadRewardDispatch admitted=x.f.plugin.getRewardDispatch(),replacement=new ServerThreadRewardDispatch(x.f.plugin);
            try {
                try {doAnswer(call->{when(x.f.plugin.getRewardDispatch()).thenReturn(replacement);return null;}).when(x.f.mysql).updateStrict(anyString(),anyList());}catch(SQLException failure){throw new AssertionError(failure);}
                when(x.rewards.givePersistedQueueRewardAsync(any(),any(),any())).thenAnswer(call->{RewardOptions options=call.getArgument(2);assertSame(admitted,options.getAsyncReplayState().getActionDispatchOwner());return CompletableFuture.completedFuture(null);});
                x.f.user.checkOfflineRewardsAsync().toCompletableFuture().join();assertEquals("",x.pending());
            }finally{replacement.close();}
        });
    }

    @Test void ordinaryAppendRetainsLegacyOldestFirstCapacityPolicy() {
        char[] text=new char[65500];Arrays.fill(text,'a');fixture(Collections.singletonList(new String(text)),x->{Reward appended=mock(Reward.class);when(appended.getRewardName()).thenReturn("new");x.f.user.addOfflineRewards(appended,new HashMap<>());assertTrue(x.pending().startsWith("\\AdvancedCoreQueue/1/normal/"));assertFalse(x.pending().contains(new String(text)));});
    }
    @Test void fullQueueCannotTrimAnActiveOccurrenceToAdmitAnotherReward() {
        char[] text=new char[65475];Arrays.fill(text,'a');String entry=new String(text)+"%asyncoccurrence%"+UUID.randomUUID();fixture(Collections.singletonList(entry),x->{CompletableFuture<Void> effect=new CompletableFuture<>();when(x.rewards.givePersistedQueueRewardAsync(any(),any(),any())).thenReturn(effect);CompletionStage<Void> result=x.f.user.checkOfflineRewardsAsync();Reward appended=mock(Reward.class);when(appended.getRewardName()).thenReturn("new");assertThrows(IllegalStateException.class,()->x.f.user.addOfflineRewards(appended,new HashMap<>()));assertEquals(entry,x.pending());effect.complete(null);result.toCompletableFuture().join();assertEquals("",x.pending());});
    }

    @Test void disabledProcessingLeavesQueueUntouched() {
        fixture(Collections.singletonList("daily"),x->{when(x.options.isProcessRewards()).thenReturn(false);x.f.user.checkOfflineRewardsAsync().toCompletableFuture().join();assertEquals("daily",x.pending());verify(x.rewards,never()).givePersistedQueueRewardAsync(any(),any(),any());});
    }
}

package com.bencodez.advancedcore.api.rewards;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.MockedConstruction;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.user.AdvancedCoreUser;

class LegacyNestedRewardSequenceTest {
    @Test void duplicateNamesAreSequentialDistinctOccurrencesAndAwaitBothEffects() {
        fixture(f -> {
            Reward child=f.reward("same");CompletableFuture<Void> first=new CompletableFuture<>(),second=new CompletableFuture<>();List<RewardOptions> calls=new ArrayList<>();
            when(child.giveRewardAsync(eq(f.user),any())).thenAnswer(c->{calls.add(c.getArgument(1));return calls.size()==1?first:second;});
            YamlConfiguration config=new YamlConfiguration();config.set("Rewards",Arrays.asList("same","same"));
            CompletionStage<Void> result=f.handler.giveRewardAsync(f.user,config,"Rewards",f.options());f.drain();
            assertEquals(1,calls.size());assertFalse(result.toCompletableFuture().isDone());assertFalse(f.writes.isEmpty());
            first.complete(null);f.drain();assertEquals(2,calls.size());assertFalse(result.toCompletableFuture().isDone());
            assertNotEquals(calls.get(0).getAsyncReplayKey(),calls.get(1).getAsyncReplayKey());
            assertEquals("occurrence",calls.get(0).getAsyncReplayOccurrenceId());assertEquals("occurrence",calls.get(1).getAsyncReplayOccurrenceId());
            second.complete(null);f.drain();await(result);
        });
    }
    @Test void retrySkipsCompletedMissingChildAndKeepsSnapshotAfterConfigurationRemoval() {
        fixture(f -> {
            Reward first=f.reward("first"),last=f.reward("last");when(first.giveRewardAsync(eq(f.user),any())).thenReturn(CompletableFuture.completedFuture(null));
            CompletableFuture<Void> failed=new CompletableFuture<>();when(last.giveRewardAsync(eq(f.user),any())).thenReturn(failed);
            YamlConfiguration config=new YamlConfiguration();config.set("Rewards",Arrays.asList("first","last"));
            CompletionStage<Void> initial=f.handler.giveRewardAsync(f.user,config,"Rewards",f.options());f.drain();failed.completeExceptionally(new IllegalStateException("last failed"));f.drain();
            assertThrows(CompletionException.class,()->await(initial));Reward.ReplayCheckpoint saved=f.writes.get(f.writes.size()-1);
            f.rewards.remove(first);config.set("Rewards",null);when(last.giveRewardAsync(eq(f.user),any())).thenReturn(CompletableFuture.completedFuture(null));
            RewardOptions retry=f.options();retry.getPlaceholders().putAll(saved.getPlaceholders());retry.setAsyncReplayProgress(saved.getReplayProgress());retry.setAsyncReplayRegistryFingerprints(saved.getReplayRegistryFingerprints());
            CompletionStage<Void> resumed=f.handler.giveRewardAsync(f.user,config,"Rewards",retry);f.drain();await(resumed);
            verify(first,times(1)).giveRewardAsync(eq(f.user),any());verify(last,times(2)).giveRewardAsync(eq(f.user),any());
        });
    }
    @Test void invalidSnapshotCursorFailsBeforeAnyChild() {
        fixture(f -> {
            Reward child=f.reward("child");RewardOptions options=f.options();Reward.ReplayState state=Reward.replayStateFor(options);state.captureRuntime(f.dispatch.plugin);
            await(Reward.replayNestedRewardSnapshot(f.dispatch.plugin,options.getPlaceholders(),"nested-list:Rewards",Arrays.asList("child"),state,"root"));
            String key=options.getPlaceholders().keySet().iterator().next().replace("_snapshot","");options.getPlaceholders().put(key,"2");
            YamlConfiguration config=new YamlConfiguration();config.set("Rewards",Arrays.asList("child"));
            CompletionStage<Void> result=f.handler.giveRewardAsync(f.user,config,"Rewards",options);f.drain();assertThrows(CompletionException.class,()->await(result));
            verify(child,never()).giveRewardAsync(any(),any());
        });
    }
    @Test void inlineBuilderKeepsPrefixSuffixAndChecksSnapshotOffOwnerBeforeAwaitingEffect() {
        fixture(f -> {
            YamlConfiguration config=new YamlConfiguration();config.set("Rewards.EXP",7);CompletableFuture<Void> effect=new CompletableFuture<>();
            try(MockedConstruction<Reward> constructed=mockConstruction(Reward.class,(mock,context)->{
                assertEquals("parent_Rewards_suffix",context.arguments().get(0));assertTrue(Bukkit.isPrimaryThread());
                doAnswer(c->{assertFalse(Bukkit.isPrimaryThread());return null;}).when(mock).checkRewardFile();when(mock.giveRewardAsync(eq(f.user),any())).thenReturn(effect);
            })) {
                CompletionStage<Void> result=new RewardBuilder(config,"Rewards").withPrefix("parent").withSuffix("suffix").sendAsync(f.user);f.drain();assertEquals(1,constructed.constructed().size());assertFalse(result.toCompletableFuture().isDone());
                effect.complete(null);f.drain();await(result);verify(constructed.constructed().get(0)).checkRewardFile();
            }
        });
    }
    @Test void emptyListIsValidButMissingDurableConfigurationFails() {
        fixture(f -> {
            YamlConfiguration config=new YamlConfiguration();config.set("Rewards",Collections.emptyList());CompletionStage<Void> empty=f.handler.giveRewardAsync(f.user,config,"Rewards",f.options());f.drain();await(empty);
            config.set("Rewards",null);CompletionStage<Void> missing=f.handler.giveRewardAsync(f.user,config,"Rewards",f.options());f.drain();assertThrows(CompletionException.class,()->await(missing));
        });
    }
    private Object await(CompletionStage<?> stage) {
        try{return stage.toCompletableFuture().get(2,TimeUnit.SECONDS);}
        catch(ExecutionException failure){throw new CompletionException(failure.getCause());}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}
        catch(TimeoutException failure){throw new AssertionError("Nested completion did not settle",failure);}
    }
    private void fixture(Consumer<Fixture> body) {
        LegacyRewardDispatchTest.Fixture dispatch=new LegacyRewardDispatchTest.Fixture();when(dispatch.plugin.getDataFolder()).thenReturn(new File(System.getProperty("java.io.tmpdir"),"legacy-nested-rewards"));when(dispatch.plugin.getRewardDispatch()).thenReturn(dispatch.owner);
        try(MockedStatic<Bukkit> bukkit=mockStatic(Bukkit.class);MockedStatic<AdvancedCorePlugin> global=mockStatic(AdvancedCorePlugin.class)) {
            global.when(AdvancedCorePlugin::getInstance).thenReturn(dispatch.plugin);bukkit.when(Bukkit::isPrimaryThread).thenAnswer(c->dispatch.primary.get());bukkit.when(Bukkit::getScheduler).thenReturn(dispatch.scheduler);
            RewardHandler handler=mock(RewardHandler.class,CALLS_REAL_METHODS);handler.plugin=dispatch.plugin;when(dispatch.plugin.getRewardHandler()).thenReturn(handler);
            List<Reward> rewards=new ArrayList<>();when(handler.getRewards()).thenReturn(rewards);when(handler.getDirectlyDefinedRewards()).thenReturn(new ArrayList<>());when(handler.getSubDirectlyDefinedRewards()).thenReturn(new ArrayList<>());
            AdvancedCoreUser user=mock(AdvancedCoreUser.class);when(user.getPlugin()).thenReturn(dispatch.plugin);when(user.getUUID()).thenReturn("user");
            try{body.accept(new Fixture(dispatch,handler,user,rewards));}finally{dispatch.owner.close();RewardHandler.getInstance().getRepeatTimer().cancel();}
        }
    }
    private static class Fixture {
        final LegacyRewardDispatchTest.Fixture dispatch;final RewardHandler handler;final AdvancedCoreUser user;final List<Reward> rewards;final List<Reward.ReplayCheckpoint> writes=new ArrayList<>();
        Fixture(LegacyRewardDispatchTest.Fixture dispatch,RewardHandler handler,AdvancedCoreUser user,List<Reward> rewards){this.dispatch=dispatch;this.handler=handler;this.user=user;this.rewards=rewards;}
        Reward reward(String name){Reward reward=mock(Reward.class);when(reward.getName()).thenReturn(name);when(reward.getConfig()).thenReturn(mock(RewardFileData.class));rewards.add(reward);return reward;}
        RewardOptions options(){RewardOptions options=new RewardOptions();options.setAsyncReplayKey("root");options.setAsyncReplayOccurrenceId("occurrence");options.setAsyncReplayCheckpointConsumer(writes::add);return options;}
        void drain(){int n=0;while(!dispatch.queued.isEmpty() || !dispatch.asyncQueued.isEmpty()){assertTrue(n++<100,"nested dispatch did not settle");if(!dispatch.queued.isEmpty())dispatch.runNext();else dispatch.runAsyncNext();}}
    }
}

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
    @Test void realRandomBuiltinZeroChanceAwaitsPickedNamedChild() {
        fixture(f->{
            Reward child=f.reward("child");CompletableFuture<Void> effect=new CompletableFuture<>();when(child.giveRewardAsync(eq(f.user),any())).thenReturn(effect);
            YamlConfiguration config=new YamlConfiguration();config.set("Random.Chance",0);config.set("Random.Rewards",Arrays.asList("child"));
            com.bencodez.advancedcore.api.rewards.injected.RewardInject inject=f.builtin("Random");Reward parent=mock(Reward.class);when(parent.getName()).thenReturn("parent");
            CompletionStage<Object> result=inject.onRewardRequestAsync(parent,f.user,config,new HashMap<>());f.drain();assertFalse(result.toCompletableFuture().isDone());effect.complete(null);f.drain();await(result);
        });
    }
    @Test void realAdvancedRandomBuiltinAwaitsSelectedNamedDefinition() {
        fixture(f->{
            Reward child=f.reward("child");CompletableFuture<Void> effect=new CompletableFuture<>();when(child.giveRewardAsync(eq(f.user),any())).thenReturn(effect);
            YamlConfiguration config=new YamlConfiguration();config.set("AdvancedRandomReward.branch","child");
            com.bencodez.advancedcore.api.rewards.injected.RewardInject inject=f.builtin("AdvancedRandomReward");Reward parent=mock(Reward.class);when(parent.getRewardName()).thenReturn("parent");
            CompletionStage<Object> result=inject.onRewardRequestAsync(parent,f.user,config,new HashMap<>());f.drain();assertFalse(result.toCompletableFuture().isDone());effect.complete(null);f.drain();assertEquals("branch",await(result));
        });
    }
    @Test void zeroChanceKeepsLegacyUnconditionalSuccessAndMissingFallbackIsOptional() {
        fixture(f->{
            assertTrue(com.bencodez.advancedcore.api.misc.MiscUtils.getInstance().checkChance(0,100));assertTrue(com.bencodez.advancedcore.api.misc.MiscUtils.getInstance().checkChance(100,100));
            assertFalse(com.bencodez.advancedcore.api.misc.MiscUtils.getInstance().checkChance(-1,100));
            YamlConfiguration config=new YamlConfiguration();config.set("Random.Chance",-1);
            com.bencodez.advancedcore.api.rewards.injected.RewardInject inject=f.builtin("Random");CompletionStage<Object> result=inject.onRewardRequestAsync(mock(Reward.class),f.user,config,new HashMap<>());f.drain();assertNull(await(result));
        });
    }
    @Test void actualInlineRandomFallbackAndAdvancedPathsAwaitTheirEffect() {
        for(String mode:Arrays.asList("inline","fallback","advanced"))fixture(f->{
            YamlConfiguration config=new YamlConfiguration();String path=mode.equals("advanced")?"AdvancedRandomReward.branch":mode.equals("fallback")?"Random.FallBack":"Random.Rewards";
            config.set(path+".EXP",7);if(!mode.equals("advanced")){config.set("Random.Chance",mode.equals("fallback")?-1:100);config.set("Random.PickRandom",false);}
            CompletableFuture<Void> effect=new CompletableFuture<>();
            try(MockedConstruction<Reward> constructed=mockConstruction(Reward.class,(mock,context)->{when(mock.giveRewardAsync(eq(f.user),any())).thenReturn(effect);})) {
                com.bencodez.advancedcore.api.rewards.injected.RewardInject inject=f.builtin(mode.equals("advanced")?"AdvancedRandomReward":"Random");Reward parent=mock(Reward.class);when(parent.getName()).thenReturn("parent");when(parent.getRewardName()).thenReturn("parent");
                CompletionStage<Object> result=inject.onRewardRequestAsync(parent,f.user,config,new HashMap<>());f.drain();assertEquals(1,constructed.constructed().size(),mode);assertFalse(result.toCompletableFuture().isDone(),mode);
                effect.complete(null);f.drain();await(result);verify(constructed.constructed().get(0)).checkRewardFile();
            }
        });
    }
    @Test void optionalBlankAndEmptyFallbacksRemainNoOps() {
        for(Object value:Arrays.asList("",Collections.emptyList()))fixture(f->{
            YamlConfiguration config=new YamlConfiguration();config.set("Random.Chance",-1);config.set("Random.FallBack",value);
            CompletionStage<Object> result=f.builtin("Random").onRewardRequestAsync(mock(Reward.class),f.user,config,new HashMap<>());f.drain();assertNull(await(result));
        });
    }
    @Test void realAdvancedRewardsBuiltinRunsChildrenSequentiallyAndAwaitsBoth() {
        fixture(f->{
            Reward first=f.reward("first"),last=f.reward("last");CompletableFuture<Void> one=new CompletableFuture<>(),two=new CompletableFuture<>();List<String> calls=new ArrayList<>();
            when(first.giveRewardAsync(eq(f.user),any())).thenAnswer(c->{calls.add("first");return one;});when(last.giveRewardAsync(eq(f.user),any())).thenAnswer(c->{calls.add("last");return two;});
            YamlConfiguration config=new YamlConfiguration();config.set("AdvancedRewards.a","first");config.set("AdvancedRewards.b","last");
            Reward parent=mock(Reward.class);when(parent.getRewardName()).thenReturn("parent");
            CompletionStage<Object> result=f.builtin("AdvancedRewards").onRewardRequestAsync(parent,f.user,config,new HashMap<>());f.drain();
            assertEquals(Arrays.asList("first"),calls);assertFalse(result.toCompletableFuture().isDone());one.complete(null);f.drain();assertEquals(Arrays.asList("first","last"),calls);assertFalse(result.toCompletableFuture().isDone());two.complete(null);f.drain();await(result);
        });
    }
    @Test void realAdvancedRewardsInlinePreservesLegacyBranchPrefixAndFailure() {
        fixture(f->{
            YamlConfiguration config=new YamlConfiguration();config.set("AdvancedRewards.branch.EXP",7);CompletableFuture<Void> effect=new CompletableFuture<>();
            try(MockedConstruction<Reward> constructed=mockConstruction(Reward.class,(mock,context)->{assertEquals("parent_AdvancedRewards_branch_branch",context.arguments().get(0));when(mock.giveRewardAsync(eq(f.user),any())).thenReturn(effect);})) {
                Reward parent=mock(Reward.class);when(parent.getRewardName()).thenReturn("parent");
                CompletionStage<Object> result=f.builtin("AdvancedRewards").onRewardRequestAsync(parent,f.user,config,new HashMap<>());f.drain();assertEquals(1,constructed.constructed().size());assertFalse(result.toCompletableFuture().isDone());
                effect.completeExceptionally(new IllegalStateException("child failure"));f.drain();assertThrows(CompletionException.class,()->await(result));
            }
        });
    }
    @Test void realJavascriptBuiltinAwaitsSelectedChildAndPreservesExpressionSubstitution() {
        for(boolean outcome:Arrays.asList(true,false))fixture(f->{
            Reward child=f.reward("child");CompletableFuture<Void> effect=new CompletableFuture<>();when(child.giveRewardAsync(eq(f.user),any())).thenReturn(effect);
            YamlConfiguration config=new YamlConfiguration();config.set("Javascript.Enabled",true);config.set("Javascript.Expression","%decision%");config.set("Javascript."+(outcome?"TrueRewards":"FalseRewards"),"child");
            HashMap<String,String> placeholders=new HashMap<>();placeholders.put("decision","true");Reward parent=mock(Reward.class);when(parent.getName()).thenReturn("parent");
            try(MockedConstruction<com.bencodez.advancedcore.api.javascript.JavascriptEngine> engines=mockConstruction(com.bencodez.advancedcore.api.javascript.JavascriptEngine.class,(mock,context)->{when(mock.addPlayer(any(org.bukkit.OfflinePlayer.class))).thenReturn(mock);when(mock.addPlayer((org.bukkit.OfflinePlayer)isNull())).thenReturn(mock);when(mock.getBooleanValue("true")).thenReturn(outcome);})) {
                CompletionStage<Object> result=f.builtin("Javascript").onRewardRequestAsync(parent,f.user,config,placeholders);f.drain();assertFalse(result.toCompletableFuture().isDone());
                verify(engines.constructed().get(0)).getBooleanValue("true");effect.complete(null);f.drain();await(result);
            }
        });
    }
    @Test void disabledJavascriptDoesNotEvaluateOrDispatchChildren() {
        fixture(f->{
            YamlConfiguration config=new YamlConfiguration();config.set("Javascript.Enabled",false);config.set("Javascript.TrueRewards","child");
            try(MockedConstruction<com.bencodez.advancedcore.api.javascript.JavascriptEngine> engines=mockConstruction(com.bencodez.advancedcore.api.javascript.JavascriptEngine.class)) {
                CompletionStage<Object> result=f.builtin("Javascript").onRewardRequestAsync(mock(Reward.class),f.user,config,new HashMap<>());f.drain();await(result);assertTrue(engines.constructed().isEmpty());
            }
        });
    }
    @Test void optionalEmptyJavascriptBranchIsAValidNoOp() {
        for(Object value:Arrays.asList(null,"",Collections.emptyList()))fixture(f->{
            YamlConfiguration config=new YamlConfiguration();config.set("Javascript.Enabled",true);config.set("Javascript.Expression","true");config.set("Javascript.TrueRewards",value);
            try(MockedConstruction<com.bencodez.advancedcore.api.javascript.JavascriptEngine> engines=mockConstruction(com.bencodez.advancedcore.api.javascript.JavascriptEngine.class,(mock,context)->{when(mock.addPlayer((org.bukkit.OfflinePlayer)isNull())).thenReturn(mock);when(mock.getBooleanValue("true")).thenReturn(true);})) {
                CompletionStage<Object> result=f.builtin("Javascript").onRewardRequestAsync(mock(Reward.class),f.user,config,new HashMap<>());f.drain();await(result);verify(f.handler,never()).getReward(anyString());
            }
        });
    }
    @Test void inlineJavascriptKeepsLegacyPrefixAndPropagatesChildFailure() {
        fixture(f->{
            YamlConfiguration config=new YamlConfiguration();config.set("Javascript.Enabled",true);config.set("Javascript.Expression","true");config.set("Javascript.TrueRewards.EXP",7);CompletableFuture<Void> effect=new CompletableFuture<>();
            try(MockedConstruction<com.bencodez.advancedcore.api.javascript.JavascriptEngine> engines=mockConstruction(com.bencodez.advancedcore.api.javascript.JavascriptEngine.class,(mock,context)->{when(mock.addPlayer((org.bukkit.OfflinePlayer)isNull())).thenReturn(mock);when(mock.getBooleanValue("true")).thenReturn(true);});
                MockedConstruction<Reward> children=mockConstruction(Reward.class,(mock,context)->{assertEquals("parent.Javascript_TrueRewards",context.arguments().get(0));when(mock.giveRewardAsync(eq(f.user),any())).thenReturn(effect);})) {
                Reward parent=mock(Reward.class);when(parent.getName()).thenReturn("parent");CompletionStage<Object> result=f.builtin("Javascript").onRewardRequestAsync(parent,f.user,config,new HashMap<>());f.drain();assertEquals(1,children.constructed().size());assertFalse(result.toCompletableFuture().isDone());
                effect.completeExceptionally(new IllegalStateException("child failed"));f.drain();assertThrows(CompletionException.class,()->await(result));
            }
        });
    }
    @Test void realLuckyBuiltinAwaitsGuaranteedChildAndPropagatesFailure() {
        fixture(f->{
            Reward child=f.reward("child");CompletableFuture<Void> effect=new CompletableFuture<>();when(child.giveRewardAsync(eq(f.user),any())).thenReturn(effect);
            YamlConfiguration config=new YamlConfiguration();config.set("Lucky.1","child");Reward parent=f.reward("parent");when(parent.getConfig().getConfigData()).thenReturn(config);
            CompletionStage<Object> result=f.builtin("Lucky").onRewardRequestAsync(parent,f.user,config,new HashMap<>());f.drain();
            verify(child).giveRewardAsync(eq(f.user),any());assertFalse(result.toCompletableFuture().isDone());
            effect.completeExceptionally(new IllegalStateException("child failure"));f.drain();assertThrows(CompletionException.class,()->await(result));
        });
    }
    @Test void luckyIgnoresNonpositiveAndNonnumericKeys() {
        fixture(f->{
            YamlConfiguration config=new YamlConfiguration();config.set("Lucky.0","child");config.set("Lucky.-1","child");config.set("Lucky.invalid","child");
            Reward parent=f.reward("parent");when(parent.getConfig().getConfigData()).thenReturn(config);
            CompletionStage<Object> result=f.builtin("Lucky").onRewardRequestAsync(parent,f.user,config,new HashMap<>());f.drain();await(result);verify(f.handler,never()).getReward(anyString());
        });
    }
    @Test void luckyKeepsDescendingDenominatorOrderAndParentOnlyOneSetting() {
        for(boolean onlyOne:Arrays.asList(false,true))fixture(f->{
            List<String> calls=new ArrayList<>();Reward common=f.reward("common"),rare=f.reward("rare");CompletableFuture<Void> first=new CompletableFuture<>();
            when(rare.giveRewardAsync(eq(f.user),any())).thenAnswer(c->{calls.add("rare");return first;});when(common.giveRewardAsync(eq(f.user),any())).thenAnswer(c->{calls.add("common");return CompletableFuture.completedFuture(null);});
            YamlConfiguration config=new YamlConfiguration();config.set("Lucky.1","common");config.set("Lucky.20","rare");config.set("OnlyOneLucky",onlyOne);config.set("Lucky.OnlyOneLucky",!onlyOne);
            Reward parent=f.reward("parent");when(parent.getConfig().getConfigData()).thenReturn(config);
            com.bencodez.advancedcore.api.misc.MiscUtils chance=mock(com.bencodez.advancedcore.api.misc.MiscUtils.class);when(chance.checkChance(eq(1.0),anyDouble())).thenReturn(true);
            try(MockedStatic<com.bencodez.advancedcore.api.misc.MiscUtils> random=mockStatic(com.bencodez.advancedcore.api.misc.MiscUtils.class)) {
                random.when(com.bencodez.advancedcore.api.misc.MiscUtils::getInstance).thenReturn(chance);
                CompletionStage<Object> result=f.builtin("Lucky").onRewardRequestAsync(parent,f.user,config,new HashMap<>());f.drain();assertEquals(Arrays.asList("rare"),calls);assertFalse(result.toCompletableFuture().isDone());
                first.complete(null);f.drain();await(result);assertEquals(onlyOne?Arrays.asList("rare"):Arrays.asList("rare","common"),calls);
                verify(chance).checkChance(1,1);verify(chance).checkChance(1,20);
            }
        });
    }
    @Test void luckyRetryDoesNotRollChanceOrOnlyOneAgain() {
        fixture(f->{
            Reward child=f.reward("child");CompletableFuture<Void> failure=new CompletableFuture<>();when(child.giveRewardAsync(eq(f.user),any())).thenReturn(failure);
            YamlConfiguration config=new YamlConfiguration();config.set("Lucky.1","child");Reward parent=f.reward("parent");when(parent.getConfig().getConfigData()).thenReturn(config);HashMap<String,String> metadata=new HashMap<>();
            com.bencodez.advancedcore.api.rewards.injected.RewardInject inject=f.builtin("Lucky");CompletionStage<Object> initial=inject.onRewardRequestAsync(parent,f.user,config,metadata);f.drain();failure.completeExceptionally(new IllegalStateException("retry"));f.drain();assertThrows(CompletionException.class,()->await(initial));
            config.set("Lucky.20","other");config.set("OnlyOneLucky",true);when(child.giveRewardAsync(eq(f.user),any())).thenReturn(CompletableFuture.completedFuture(null));
            com.bencodez.advancedcore.api.misc.MiscUtils chance=mock(com.bencodez.advancedcore.api.misc.MiscUtils.class);
            try(MockedStatic<com.bencodez.advancedcore.api.misc.MiscUtils> random=mockStatic(com.bencodez.advancedcore.api.misc.MiscUtils.class)) {
                random.when(com.bencodez.advancedcore.api.misc.MiscUtils::getInstance).thenReturn(chance);CompletionStage<Object> retry=inject.onRewardRequestAsync(parent,f.user,config,metadata);f.drain();await(retry);verifyNoInteractions(chance);verify(child,times(2)).giveRewardAsync(eq(f.user),any());
            }
        });
    }
    @Test void realAdvancedWorldBuiltinAwaitsChildrenAndPreservesWorldConstraintAndPrefix() {
        fixture(f->{
            YamlConfiguration config=new YamlConfiguration();config.set("AdvancedWorld.world.EXP",7);config.set("AdvancedWorld.nether.EXP",8);CompletableFuture<Void> first=new CompletableFuture<>(),last=new CompletableFuture<>();List<String> names=new ArrayList<>();
            try(MockedConstruction<Reward> children=mockConstruction(Reward.class,(mock,context)->{
                String name=(String)context.arguments().get(0);org.bukkit.configuration.ConfigurationSection section=(org.bukkit.configuration.ConfigurationSection)context.arguments().get(1);
                assertTrue(Bukkit.isPrimaryThread());assertEquals(Arrays.asList(name.endsWith("_world")?"world":"nether"),section.getStringList("Worlds"));names.add(name);
                when(mock.giveRewardAsync(eq(f.user),any())).thenReturn(name.endsWith("_world")?first:last);
            })) {
                Reward parent=mock(Reward.class);when(parent.getName()).thenReturn("parent");
                CompletionStage<Object> result=f.builtin("AdvancedWorld").onRewardRequestAsync(parent,f.user,config,new HashMap<>());f.drain();assertEquals(Arrays.asList("parent_AdvancedWorld_world"),names);assertFalse(result.toCompletableFuture().isDone());
                first.complete(null);f.drain();assertEquals(Arrays.asList("parent_AdvancedWorld_world","parent_AdvancedWorld_nether"),names);assertFalse(result.toCompletableFuture().isDone());
                last.completeExceptionally(new IllegalStateException("world child failure"));f.drain();assertThrows(CompletionException.class,()->await(result));
            }
        });
    }
    @Test void absentOrEmptyAdvancedWorldRemainsANoOp() {
        for(boolean empty:Arrays.asList(false,true))fixture(f->{
            YamlConfiguration config=new YamlConfiguration();if(empty)config.createSection("AdvancedWorld");
            CompletionStage<Object> result=f.builtin("AdvancedWorld").onRewardRequestAsync(mock(Reward.class),f.user,config,new HashMap<>());f.drain();await(result);verify(f.handler,never()).getReward(anyString());
        });
    }
    @Test void commandReplayFreezesExpandedPayloadAndSkipsAcknowledgedCommandsOnRetry() {
        fixture(f->{
            RewardOptions options=f.options();Reward.ReplayState state=Reward.replayStateFor(options);state.captureRuntime(f.dispatch.plugin);List<String> calls=new ArrayList<>();CompletableFuture<Void> first=new CompletableFuture<>(),last=new CompletableFuture<>();
            CompletionStage<Void> initial=Reward.replayCommandSequence(f.dispatch.plugin,options.getPlaceholders(),"console",Arrays.asList("first %value%","last %value%"),Arrays.asList("first old","last old"),state,"root",(command,index)->{assertFalse(f.writes.isEmpty());calls.add(command);return index==0?first:last;});f.drain();assertEquals(Arrays.asList("first old"),calls);assertFalse(initial.toCompletableFuture().isDone());
            first.complete(null);f.drain();assertEquals(Arrays.asList("first old","last old"),calls);assertFalse(initial.toCompletableFuture().isDone());last.completeExceptionally(new IllegalStateException("last failed"));f.drain();assertThrows(CompletionException.class,()->await(initial));
            Reward.ReplayCheckpoint saved=f.writes.get(f.writes.size()-1);RewardOptions retry=f.options();retry.getPlaceholders().putAll(saved.getPlaceholders());retry.setAsyncReplayProgress(saved.getReplayProgress());retry.setAsyncReplayRegistryFingerprints(saved.getReplayRegistryFingerprints());Reward.ReplayState restored=Reward.replayStateFor(retry);restored.captureRuntime(f.dispatch.plugin);
            CompletionStage<Void> resumed=Reward.replayCommandSequence(f.dispatch.plugin,retry.getPlaceholders(),"console",Arrays.asList("changed"),Arrays.asList("changed new"),restored,"root",(command,index)->{calls.add(command);return CompletableFuture.completedFuture(null);});f.drain();await(resumed);assertEquals(Arrays.asList("first old","last old","last old"),calls);
        });
    }
    @Test void commandSnapshotPublicationFailurePreventsPhysicalDispatch() {
        fixture(f->{
            RewardOptions options=f.options();options.setAsyncReplayCheckpointConsumer(checkpoint->{throw new IllegalStateException("storage failed");});Reward.ReplayState state=Reward.replayStateFor(options);state.captureRuntime(f.dispatch.plugin);List<String> calls=new ArrayList<>();
            CompletionStage<Void> result=Reward.replayCommandSequence(f.dispatch.plugin,options.getPlaceholders(),"console",Arrays.asList("command"),Arrays.asList("expanded"),state,"root",(command,index)->{calls.add(command);return CompletableFuture.completedFuture(null);});f.drain();assertThrows(CompletionException.class,()->await(result));assertTrue(calls.isEmpty());
        });
    }
    @Test void commandReplayRejectsMalformedCursorAndMismatchedExpansion() {
        fixture(f->{
            List<String> calls=new ArrayList<>();CompletionStage<Void> mismatch=Reward.replayCommandSequence(f.dispatch.plugin,new HashMap<>(),"console",Arrays.asList("command"),Collections.emptyList(),null,null,(command,index)->{calls.add(command);return CompletableFuture.completedFuture(null);});assertThrows(CompletionException.class,()->await(mismatch));assertTrue(calls.isEmpty());
            RewardOptions options=f.options();Reward.ReplayState state=Reward.replayStateFor(options);state.captureRuntime(f.dispatch.plugin);CompletionStage<Void> completed=Reward.replayCommandSequence(f.dispatch.plugin,options.getPlaceholders(),"console",Arrays.asList("command"),Arrays.asList("expanded"),state,"root",(command,index)->CompletableFuture.completedFuture(null));f.drain();await(completed);
            String snapshot=options.getPlaceholders().keySet().stream().filter(key->key.endsWith("_snapshot")).findFirst().get();String cursor=snapshot.substring(0,snapshot.length()-"_snapshot".length());options.getPlaceholders().put(cursor,"99");
            RewardOptions retry=f.options();retry.getPlaceholders().putAll(options.getPlaceholders());Reward.ReplayState restored=Reward.replayStateFor(retry);restored.captureRuntime(f.dispatch.plugin);CompletionStage<Void> invalid=Reward.replayCommandSequence(f.dispatch.plugin,retry.getPlaceholders(),"console",Arrays.asList("command"),Arrays.asList("expanded"),restored,"root",(command,index)->{calls.add(command);return CompletableFuture.completedFuture(null);});f.drain();assertThrows(CompletionException.class,()->await(invalid));assertTrue(calls.isEmpty());
        });
    }
    @Test void commandRetryRepublishesUnacknowledgedInMemorySnapshotBeforeDispatch() {
        fixture(f->{
            java.util.concurrent.atomic.AtomicInteger writes=new java.util.concurrent.atomic.AtomicInteger();RewardOptions options=f.options();options.setAsyncReplayCheckpointConsumer(checkpoint->{if(writes.incrementAndGet()==1)throw new IllegalStateException("first publication failed");});Reward.ReplayState state=Reward.replayStateFor(options);state.captureRuntime(f.dispatch.plugin);List<String> calls=new ArrayList<>();
            java.util.function.BiFunction<String,Integer,CompletionStage<Void>> physical=(command,index)->{assertTrue(writes.get()>=2,"Retry must acknowledge snapshot before issuing command");calls.add(command);return CompletableFuture.completedFuture(null);};
            CompletionStage<Void> initial=Reward.replayCommandSequence(f.dispatch.plugin,options.getPlaceholders(),"console",Arrays.asList("template"),Arrays.asList("original expansion"),state,"root",physical);f.drain();assertThrows(CompletionException.class,()->await(initial));assertTrue(calls.isEmpty());
            CompletionStage<Void> retry=Reward.replayCommandSequence(f.dispatch.plugin,options.getPlaceholders(),"console",Arrays.asList("changed template"),Arrays.asList("changed expansion"),state,"root",physical);f.drain();await(retry);assertEquals(Arrays.asList("original expansion"),calls);
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
            java.lang.reflect.Field miscPlugin;
            try{miscPlugin=com.bencodez.advancedcore.api.misc.MiscUtils.class.getDeclaredField("plugin");miscPlugin.setAccessible(true);}catch(Exception failure){throw new AssertionError(failure);}
            Object previousMiscPlugin;
            try{previousMiscPlugin=miscPlugin.get(com.bencodez.advancedcore.api.misc.MiscUtils.getInstance());miscPlugin.set(com.bencodez.advancedcore.api.misc.MiscUtils.getInstance(),dispatch.plugin);}catch(Exception failure){throw new AssertionError(failure);}
            try{body.accept(new Fixture(dispatch,handler,user,rewards));}finally{
                try{miscPlugin.set(com.bencodez.advancedcore.api.misc.MiscUtils.getInstance(),previousMiscPlugin);}catch(Exception failure){throw new AssertionError(failure);}
                dispatch.owner.close();RewardHandler.getInstance().getRepeatTimer().cancel();
            }
        }
    }
    private static class Fixture {
        final LegacyRewardDispatchTest.Fixture dispatch;final RewardHandler handler;final AdvancedCoreUser user;final List<Reward> rewards;final List<Reward.ReplayCheckpoint> writes=new ArrayList<>();
        Fixture(LegacyRewardDispatchTest.Fixture dispatch,RewardHandler handler,AdvancedCoreUser user,List<Reward> rewards){this.dispatch=dispatch;this.handler=handler;this.user=user;this.rewards=rewards;}
        com.bencodez.advancedcore.api.rewards.injected.RewardInject builtin(String path) {
            ArrayList<com.bencodez.advancedcore.api.rewards.injected.RewardInject> registry=new ArrayList<>();
            try{java.lang.reflect.Field field=RewardHandler.class.getDeclaredField("injectedRewards");field.setAccessible(true);field.set(handler,registry);}catch(Exception failure){throw new AssertionError(failure);}
            when(handler.getInjectedRewards()).thenReturn(registry);handler.loadInjectedRewards();
            return registry.stream().filter(i->i.getPath().equals(path)).findFirst().get();
        }
        Reward reward(String name){Reward reward=mock(Reward.class);when(reward.getName()).thenReturn(name);when(reward.getConfig()).thenReturn(mock(RewardFileData.class));rewards.add(reward);return reward;}
        RewardOptions options(){RewardOptions options=new RewardOptions();options.setAsyncReplayKey("root");options.setAsyncReplayOccurrenceId("occurrence");options.setAsyncReplayCheckpointConsumer(writes::add);return options;}
        void drain(){int n=0;while(!dispatch.queued.isEmpty() || !dispatch.asyncQueued.isEmpty()){assertTrue(n++<100,"nested dispatch did not settle");if(!dispatch.queued.isEmpty())dispatch.runNext();else dispatch.runAsyncNext();}}
    }
}

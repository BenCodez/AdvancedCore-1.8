package com.bencodez.advancedcore.api.rewards;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.rewards.injected.RewardInject;
import com.bencodez.advancedcore.api.user.AdvancedCoreUser;

class LegacyOrderedRewardPipelineTest {
    @Test void emptyLegacyActionCollectionDoesNotPublishReplayMetadataOrCheckpoint() {
        fixture(f -> {
            AdvancedCoreUser user=scopedUser(f);
            HashMap<String,String> placeholders=new HashMap<>();
            placeholders.put("operator-value","retained");
            RewardOptions options=new RewardOptions();
            options.setAsyncReplayCheckpointConsumer(checkpoint->fail("empty collection must not checkpoint"));
            AdvancedCoreUser.AsyncActionCollection collection=user.beginAsyncActionCollection(
                    Reward.replayStateFor(options),placeholders,"AsyncReward/0");
            CompletionStage<Void> completion=user.endAsyncActionCollection(collection);
            drain(f);
            assertTrue(completion.toCompletableFuture().isDone());
            completion.toCompletableFuture().join();
            assertEquals(java.util.Collections.singletonMap("operator-value","retained"),placeholders);
            assertSame(completion,user.endAsyncActionCollection(collection));
        });
    }

    @Test void waitsForPhysicalEffectThenPublishesPlaceholderBeforeNextAndPostSteps() {
        fixture(f -> {
            List<String> calls=new ArrayList<>();CompletableFuture<Object> physical=new CompletableFuture<>();
            RewardInject first=async("first",p->{calls.add("first");return physical;});first.asPlaceholder("result");
            RewardInject post=async("post",p->{assertEquals("committed",p.get("result"));calls.add("post");return CompletableFuture.completedFuture(null);});post.postReward();
            RewardInject second=async("second",p->{assertEquals("committed",p.get("result"));calls.add("second");return CompletableFuture.completedFuture(null);});
            f.injections.addAll(Arrays.asList(first,post,second));HashMap<String,String> placeholders=new HashMap<>();
            CompletionStage<Void> result=f.reward.giveInjectedRewardsAsync(null,placeholders);
            assertTrue(calls.isEmpty());f.dispatch.runNext();assertEquals(Arrays.asList("first"),calls);
            assertFalse(result.toCompletableFuture().isDone());assertFalse(placeholders.containsKey("result"));
            physical.complete("committed");assertFalse(result.toCompletableFuture().isDone());assertEquals(Arrays.asList("first"),calls);
            f.dispatch.runNext();result.toCompletableFuture().join();assertEquals(Arrays.asList("first","second","post"),calls);
        });
    }
    @Test void asynchronousFailureStopsSubsequentEffectsAndPostSteps() {
        fixture(f -> {
            CompletableFuture<Object> physical=new CompletableFuture<>();List<String> calls=new ArrayList<>();
            f.injections.add(async("first",p->physical));f.injections.add(async("second",p->{calls.add("second");return CompletableFuture.completedFuture(null);}));
            RewardInject post=async("post",p->{calls.add("post");return CompletableFuture.completedFuture(null);});post.postReward();f.injections.add(post);
            CompletionStage<Void> result=f.reward.giveInjectedRewardsAsync(null,new HashMap<>());f.dispatch.runNext();
            IllegalStateException failure=new IllegalStateException("not committed");physical.completeExceptionally(failure);
            assertSame(failure,assertThrows(CompletionException.class,()->result.toCompletableFuture().join()).getCause());assertTrue(calls.isEmpty());
        });
    }
    @Test void closedGenerationCannotContinueThroughReplacementDispatcher() {
        fixture(f -> {
            CompletableFuture<Object> physical=new CompletableFuture<>();List<String> calls=new ArrayList<>();
            f.injections.add(async("first",p->physical));f.injections.add(async("second",p->{calls.add("second");return CompletableFuture.completedFuture(null);}));
            CompletionStage<Void> result=f.reward.giveInjectedRewardsAsync(null,new HashMap<>());f.dispatch.runNext();
            f.dispatch.owner.close();ServerThreadRewardDispatch replacement=new ServerThreadRewardDispatch(f.dispatch.plugin);
            when(f.dispatch.plugin.getRewardDispatch()).thenReturn(replacement);
            physical.complete("committed");assertThrows(CompletionException.class,()->result.toCompletableFuture().join());assertTrue(calls.isEmpty());replacement.close();
        });
    }
    @Test void ordinaryLegacyCallbackUsesSynchronousHookAndPreservesItsResult() {
        fixture(f -> {
            f.injections.add(new RewardInject("legacy") {
                @Override public Object onRewardRequest(Reward r,AdvancedCoreUser u,ConfigurationSection data,HashMap<String,String> p) {assertTrue(Bukkit.isPrimaryThread());return 7;}
                @Override public CompletionStage<Object> onRewardRequestAsync(Reward r,AdvancedCoreUser u,ConfigurationSection data,HashMap<String,String> p) {throw new AssertionError("legacy must not opt in");}
            }.asPlaceholder("points"));
            HashMap<String,String> placeholders=new HashMap<>();CompletionStage<Void> result=f.reward.giveInjectedRewardsAsync(null,placeholders);
            f.dispatch.runNext();result.toCompletableFuture().join();assertEquals("7",placeholders.get("points"));
        });
    }
    @Test void nullAsynchronousStageStopsThePipeline() {
        fixture(f -> {
            f.injections.add(async("null",p->null));
            CompletionStage<Void> result=f.reward.giveInjectedRewardsAsync(null,new HashMap<>());f.dispatch.runNext();
            assertInstanceOf(IllegalStateException.class,assertThrows(CompletionException.class,()->result.toCompletableFuture().join()).getCause());
        });
    }
    @Test void ordinaryLegacyExceptionIsIsolatedAndTheNextCallbackStillRuns() {
        fixture(f -> {
            List<String> calls=new ArrayList<>();
            f.injections.add(new RewardInject("legacy") {
                @Override public Object onRewardRequest(Reward r,AdvancedCoreUser u,ConfigurationSection data,HashMap<String,String> p) {
                    throw new IllegalArgumentException("expected legacy callback isolation");
                }
            });
            f.injections.add(async("next",p->{calls.add("next");return CompletableFuture.completedFuture(null);}));
            CompletionStage<Void> result=f.reward.giveInjectedRewardsAsync(null,new HashMap<>());f.dispatch.runNext();
            result.toCompletableFuture().join();assertEquals(Arrays.asList("next"),calls);
        });
    }
    @Test void awaitedUserDeliveryCopiesCallerStateAndSchedulesRepeatAfterSettlement() {
        fixture(f -> {
            CompletableFuture<Object> physical=new CompletableFuture<>();
            AdvancedCoreUser user=onlineUser();RepeatHandle repeat=repeat(f.reward);
            f.injections.add(async("first",p->{assertEquals("original",p.get("token"));assertEquals("User",p.get("player"));return physical;}));
            HashMap<String,String> input=new HashMap<>();input.put("token","original");RewardOptions options=new RewardOptions();
            CompletionStage<Void> result=f.reward.giveRewardUserAsync(user,input,options);
            input.put("token","changed");options.setCheckRepeat(false);f.dispatch.runUserPreparation();
            verify(repeat,never()).giveRepeat(any(),any());assertFalse(result.toCompletableFuture().isDone());
            physical.complete("done");f.dispatch.runNext();result.toCompletableFuture().join();
            verify(repeat).giveRepeat(f.dispatch.plugin,user);assertFalse(input.containsKey("player"));
        });
    }
    @Test void failedUserDeliveryDoesNotScheduleRepeat() {
        fixture(f -> {
            AdvancedCoreUser user=onlineUser();RepeatHandle repeat=repeat(f.reward);CompletableFuture<Object> physical=new CompletableFuture<>();
            f.injections.add(async("first",p->physical));CompletionStage<Void> result=f.reward.giveRewardUserAsync(user,null,new RewardOptions());
            f.dispatch.runUserPreparation();physical.completeExceptionally(new IllegalStateException("not committed"));
            assertThrows(CompletionException.class,()->result.toCompletableFuture().join());verify(repeat,never()).giveRepeat(any(),any());
        });
    }
    @Test void unavailablePlayerFailsAwaitedDeliveryWithoutInvokingInjections() {
        fixture(f -> {
            AdvancedCoreUser user=mock(AdvancedCoreUser.class);when(user.getPlayerName()).thenReturn("Offline");List<String> calls=new ArrayList<>();
            f.injections.add(async("first",p->{calls.add("first");return CompletableFuture.completedFuture(null);}));
            CompletionStage<Void> result=f.reward.giveRewardUserAsync(user,null,new RewardOptions());f.dispatch.runUserPreparation();
            assertInstanceOf(IllegalStateException.class,assertThrows(CompletionException.class,()->result.toCompletableFuture().join()).getCause());assertTrue(calls.isEmpty());
        });
    }
    @Test void voidUserDeliveryRoutesOptedInInjectionThroughActualAsyncHook() {
        fixture(f -> {
            AdvancedCoreUser user=onlineUser();RepeatHandle repeat=repeat(f.reward);CompletableFuture<Object> physical=new CompletableFuture<>();
            f.injections.add(async("first",p->physical));f.reward.giveRewardUser(user,new HashMap<>(),new RewardOptions());
            assertEquals(1,f.dispatch.queued.size());f.dispatch.runUserPreparation();verify(repeat,never()).giveRepeat(any(),any());
            physical.complete("done");f.dispatch.runNext();verify(repeat).giveRepeat(f.dispatch.plugin,user);
        });
    }
    @Test void synchronousLegacyUserDeliveryKeepsItsInlineCallback() {
        fixture(f -> {
            AdvancedCoreUser user=onlineUser();RepeatHandle repeat=repeat(f.reward);List<String> calls=new ArrayList<>();
            f.injections.add(new RewardInject("legacy") {
                @Override public Object onRewardRequest(Reward r,AdvancedCoreUser u,ConfigurationSection data,HashMap<String,String> p){calls.add("legacy");return null;}
            });
            f.reward.giveRewardUser(user,new HashMap<>(),new RewardOptions().setCheckRepeat(false));
            assertEquals(Arrays.asList("legacy"),calls);assertTrue(f.dispatch.queued.isEmpty());verify(repeat,never()).giveRepeat(any(),any());
        });
    }
    @Test void absentConfiguredAsyncFeatureKeepsTheLegacyPath() {
        fixture(f -> {
            AdvancedCoreUser user=onlineUser();repeat(f.reward);List<String> calls=new ArrayList<>();
            f.injections.add(new RewardInject("absent") {
                @Override public boolean supportsAsyncRequest(){return true;}
                @Override public boolean requiresConfiguredDataForAsync(){return true;}
                @Override public Object onRewardRequest(Reward r,AdvancedCoreUser u,ConfigurationSection data,HashMap<String,String> p){calls.add("legacy");return null;}
                @Override public CompletionStage<Object> onRewardRequestAsync(Reward r,AdvancedCoreUser u,ConfigurationSection data,HashMap<String,String> p){throw new AssertionError("Absent feature must not route all rewards async");}
            });
            f.reward.giveRewardUser(user,new HashMap<>(),new RewardOptions().setCheckRepeat(false));assertEquals(Arrays.asList("legacy"),calls);assertTrue(f.dispatch.queued.isEmpty());
        });
    }
    @Test void forceOfflineConfigAllowsPlayerlessAwaitedUserDelivery() {
        fixture(f -> {
            AdvancedCoreUser user=mock(AdvancedCoreUser.class);when(user.getPlayerName()).thenReturn("Offline");when(user.getUUID()).thenReturn("uuid");
            f.reward.setForceOffline(true);repeat(f.reward);List<String> calls=new ArrayList<>();
            f.injections.add(async("first",p->{assertEquals("Offline",p.get("player"));calls.add("first");return CompletableFuture.completedFuture(null);}));
            CompletionStage<Void> result=f.reward.giveRewardUserAsync(user,null,new RewardOptions().setCheckRepeat(false));
            f.dispatch.runUserPreparation();result.toCompletableFuture().join();assertEquals(Arrays.asList("first"),calls);
        });
    }
    @Test void userIdentityPreflightReadsOnceOffOwnerAndKeepsRegistrationSnapshot() {
        fixture(f -> {
            AdvancedCoreUser user=onlineUser();when(user.getPlayerName()).thenAnswer(ignored->{assertFalse(Bukkit.isPrimaryThread());return "User";});repeat(f.reward);
            List<String> calls=new ArrayList<>();f.injections.add(async("first",p->{assertEquals("User",p.get("player"));calls.add("first");return CompletableFuture.completedFuture(null);}));
            CompletionStage<Void> result=f.reward.giveRewardUserAsync(user,null,new RewardOptions().setCheckRepeat(false));
            f.dispatch.runNext();f.injections.clear();f.dispatch.runAsyncNext();f.dispatch.runNext();
            result.toCompletableFuture().join();assertEquals(Arrays.asList("first"),calls);verify(user,times(1)).getPlayerName();
        });
    }
    @Test void identityPreflightFailureDoesNotStartInjectionsOrRepeats() {
        fixture(f -> {
            AdvancedCoreUser user=onlineUser();IllegalStateException failure=new IllegalStateException("identity storage unavailable");when(user.getPlayerName()).thenThrow(failure);
            RepeatHandle repeat=repeat(f.reward);List<String> calls=new ArrayList<>();f.injections.add(async("first",p->{calls.add("first");return CompletableFuture.completedFuture(null);}));
            CompletionStage<Void> result=f.reward.giveRewardUserAsync(user,null,new RewardOptions());f.dispatch.runNext();f.dispatch.runAsyncNext();
            assertSame(failure,assertThrows(CompletionException.class,()->result.toCompletableFuture().join()).getCause());assertTrue(calls.isEmpty());verify(repeat,never()).giveRepeat(any(),any());
        });
    }

    @Test void legacyItemInjectionCannotAdvanceBeforeItsDeliveryReceipt() {
        fixture(f -> {
            AdvancedCoreUser user=mock(AdvancedCoreUser.class,CALLS_REAL_METHODS);org.bukkit.entity.Player player=mock(org.bukkit.entity.Player.class);
            doReturn(player).when(user).getPlayer();
            try {java.lang.reflect.Field field=AdvancedCoreUser.class.getDeclaredField("plugin");field.setAccessible(true);field.set(user,f.dispatch.plugin);}catch(Exception error){throw new AssertionError(error);}
            com.bencodez.simpleapi.scheduler.BukkitScheduler scheduler=mock(com.bencodez.simpleapi.scheduler.BukkitScheduler.class);
            when(f.dispatch.plugin.getBukkitScheduler()).thenReturn(scheduler);
            com.bencodez.advancedcore.api.item.FullInventoryHandler items=mock(com.bencodez.advancedcore.api.item.FullInventoryHandler.class);
            when(f.dispatch.plugin.getFullInventoryHandler()).thenReturn(items);
            org.bukkit.inventory.ItemStack item=new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND,3);CompletableFuture<Void> delivered=new CompletableFuture<>();
            when(items.giveItemAsync(player,item)).thenReturn(delivered);List<String> calls=new ArrayList<>();
            f.injections.add(new RewardInject("legacy-item") {@Override public Object onRewardRequest(Reward reward,AdvancedCoreUser target,ConfigurationSection config,HashMap<String,String> placeholders){target.giveItem(item);calls.add("item");return null;}});
            f.injections.add(async("after",p->{calls.add("after");return CompletableFuture.completedFuture(null);}));
            CompletionStage<Void> result=f.reward.giveInjectedRewardsAsync(user,new HashMap<>());f.dispatch.runNext();
            assertEquals(Arrays.asList("item"),calls);assertFalse(result.toCompletableFuture().isDone());
            delivered.complete(null);f.dispatch.runNext();result.toCompletableFuture().join();assertEquals(Arrays.asList("item","after"),calls);
        });
    }


    @Test void explicitlyWrappedAsyncContinuationBelongsToItsOriginatingInjection() {
        fixture(f -> {
            AdvancedCoreUser user=scopedUser(f);org.bukkit.entity.Player player=user.getPlayer();
            com.bencodez.advancedcore.api.item.FullInventoryHandler items=mock(com.bencodez.advancedcore.api.item.FullInventoryHandler.class);when(f.dispatch.plugin.getFullInventoryHandler()).thenReturn(items);
            org.bukkit.inventory.ItemStack item=new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND,3);CompletableFuture<Void> delivered=new CompletableFuture<>();when(items.giveItemAsync(player,item)).thenReturn(delivered);
            CompletableFuture<Object> gate=new CompletableFuture<>();
            f.injections.add(async("continuation",p->{AdvancedCoreUser.AsyncActionContext context=user.captureAsyncActionContext();return gate.thenApply(context.wrap(value->{user.giveItem(item);return value;}));}));
            CompletionStage<Void> result=f.reward.giveInjectedRewardsAsync(user,new HashMap<>());f.dispatch.runNext();assertFalse(result.toCompletableFuture().isDone());
            gate.complete("ready");assertFalse(result.toCompletableFuture().isDone());verify(items).giveItemAsync(player,item);
            delivered.complete(null);f.dispatch.runNext();result.toCompletableFuture().join();
        });
    }
    @Test void pendingCollectionDoesNotCaptureAnUnrelatedOrdinaryDelivery() {
        fixture(f -> {
            AdvancedCoreUser user=scopedUser(f);org.bukkit.entity.Player player=user.getPlayer();
            com.bencodez.advancedcore.api.item.FullInventoryHandler items=mock(com.bencodez.advancedcore.api.item.FullInventoryHandler.class);when(f.dispatch.plugin.getFullInventoryHandler()).thenReturn(items);
            org.bukkit.inventory.ItemStack item=new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND,3);
            AdvancedCoreUser.AsyncActionCollection collection=user.beginAsyncActionCollection();AdvancedCoreUser.AsyncActionContext context=user.captureAsyncActionContext();user.restoreAsyncActionCollectionScope(collection);
            context.wrap((Runnable)()->user.giveItem(item)).run();
            user.giveItem(item);verify(items).giveItem(player,item);verify(items,never()).giveItemAsync(any(),any());
            CompletableFuture<Void> delivered=new CompletableFuture<>();when(items.giveItemAsync(player,item)).thenReturn(delivered);
            CompletionStage<Void> result=user.endAsyncActionCollection(collection);assertFalse(result.toCompletableFuture().isDone());delivered.complete(null);result.toCompletableFuture().join();
        });
    }
    @Test void legacyActionCheckpointSkipsOnlyTheExactCompletedPayload() {
        fixture(f -> {
            AdvancedCoreUser user=scopedUser(f);org.bukkit.entity.Player player=user.getPlayer();
            com.bencodez.advancedcore.api.item.FullInventoryHandler items=mock(com.bencodez.advancedcore.api.item.FullInventoryHandler.class);when(f.dispatch.plugin.getFullInventoryHandler()).thenReturn(items);
            org.bukkit.inventory.ItemStack item=new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND,3);when(items.giveItemAsync(player,item)).thenReturn(CompletableFuture.completedFuture(null));
            Reward.ReplayState state=replayState();HashMap<String,String> metadata=new HashMap<>();List<Reward.ReplayCheckpoint> writes=new ArrayList<>();
            try {java.lang.reflect.Method set=Reward.ReplayState.class.getDeclaredMethod("setCheckpointConsumer",java.util.function.Consumer.class);set.setAccessible(true);set.invoke(state,(java.util.function.Consumer<Reward.ReplayCheckpoint>)writes::add);}catch(Exception failure){throw new AssertionError(failure);}
            AdvancedCoreUser.AsyncActionCollection first=user.beginAsyncActionCollection(state,metadata,"inject");user.giveItem(item);user.endAsyncActionCollection(first).toCompletableFuture().join();
            assertEquals(2,writes.size());verify(items).giveItemAsync(player,item);
            AdvancedCoreUser.AsyncActionCollection retry=user.beginAsyncActionCollection(state,metadata,"inject");user.giveItem(item);user.endAsyncActionCollection(retry).toCompletableFuture().join();verify(items,times(1)).giveItemAsync(player,item);assertEquals(2,writes.size());
        });
    }
    @Test void duplicateScopeCloseCannotRepeatAnAlreadyAcceptedEffect() {
        fixture(f -> {
            AdvancedCoreUser user=scopedUser(f);org.bukkit.entity.Player player=user.getPlayer();com.bencodez.advancedcore.api.item.FullInventoryHandler items=mock(com.bencodez.advancedcore.api.item.FullInventoryHandler.class);when(f.dispatch.plugin.getFullInventoryHandler()).thenReturn(items);
            org.bukkit.inventory.ItemStack item=new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND,3);CompletableFuture<Void> delivered=new CompletableFuture<>();when(items.giveItemAsync(player,item)).thenReturn(delivered);
            AdvancedCoreUser.AsyncActionCollection collection=user.beginAsyncActionCollection();user.giveItem(item);
            CompletionStage<Void> first=user.endAsyncActionCollection(collection),second=user.endAsyncActionCollection(collection);assertEquals(1,mockingDetails(items).getInvocations().size());
            delivered.complete(null);first.toCompletableFuture().join();second.toCompletableFuture().join();
        });
    }
    @Test void rootRejectsCountOnlyCheckpointBeforeAnyEffect() {
        fixture(f -> {
            AdvancedCoreUser user=onlineUser();repeat(f.reward);List<String> effects=new ArrayList<>();
            f.injections.add(async("effect",p->{effects.add("executed");return CompletableFuture.completedFuture(null);}));
            RewardOptions options=new RewardOptions().setCheckRepeat(false);options.setLegacyAsyncReplayCheckpoint(true);
            CompletionStage<Void> result=f.reward.giveRewardUserAsync(user,new HashMap<>(),options);
            f.dispatch.runUserPreparation();
            assertThrows(CompletionException.class,()->result.toCompletableFuture().join());assertTrue(effects.isEmpty());
        });
    }

    @Test void checkpointNotificationWaitsForDurableWriteAndSettlementBeforeNextEffect() {
        fixture(f -> {
            AdvancedCoreUser user=onlineUser();repeat(f.reward);doReturn("root").when(f.reward).getRewardName();
            List<String> phases=new ArrayList<>();CompletableFuture<Void> notification=new CompletableFuture<>();
            f.injections.add(new RewardInject("first") {
                @Override public Object onRewardRequest(Reward r,AdvancedCoreUser u,ConfigurationSection data,HashMap<String,String> p) {
                    phases.add("effect");return null;
                }
                @Override public CompletionStage<Void> onReplayCheckpointPersisted(Reward r,AdvancedCoreUser u,String occurrence,String key) {
                    assertTrue(Bukkit.isPrimaryThread());assertEquals("stable",occurrence);assertEquals("root/0",key);
                    assertEquals(Arrays.asList("effect","write-1"),phases);phases.add("notify");return notification;
                }
            });
            f.injections.add(async("second",p->{phases.add("second");return CompletableFuture.completedFuture(null);}));
            RewardOptions options=new RewardOptions().setCheckRepeat(false);options.setAsyncReplayOccurrenceId("stable");
            options.setAsyncReplayCheckpointConsumer(checkpoint->{assertFalse(Bukkit.isPrimaryThread());phases.add("write-"+checkpoint.getReplayProgress().get("root"));});
            CompletionStage<Void> result=f.reward.giveRewardUserAsync(user,new HashMap<>(),options);drain(f);
            assertEquals(Arrays.asList("effect","write-1","notify"),phases);assertFalse(result.toCompletableFuture().isDone());
            notification.complete(null);drain(f);result.toCompletableFuture().join();
            assertEquals(Arrays.asList("effect","write-1","notify","second","write-2"),phases);
        });
    }

    @Test void failedCheckpointNotificationRecoveryRetriesHookWithoutRepeatingCompletedEffect() {
        fixture(f -> {
            AdvancedCoreUser user=onlineUser();repeat(f.reward);doReturn("root").when(f.reward).getRewardName();
            List<String> phases=new ArrayList<>();java.util.concurrent.atomic.AtomicInteger notifications=new java.util.concurrent.atomic.AtomicInteger();
            f.injections.add(new RewardInject("first") {
                @Override public Object onRewardRequest(Reward r,AdvancedCoreUser u,ConfigurationSection data,HashMap<String,String> p) {
                    phases.add("effect");return null;
                }
                @Override public CompletionStage<Void> onReplayCheckpointPersisted(Reward r,AdvancedCoreUser u,String occurrence,String key) {
                    assertTrue(Bukkit.isPrimaryThread());assertEquals("stable",occurrence);assertEquals("root/0",key);
                    phases.add("notify");
                    if(notifications.incrementAndGet()==1) {
                        CompletableFuture<Void> failed=new CompletableFuture<>();failed.completeExceptionally(new IllegalStateException("retirement unavailable"));return failed;
                    }
                    return CompletableFuture.completedFuture(null);
                }
            });
            f.injections.add(async("second",p->{phases.add("second");return CompletableFuture.completedFuture(null);}));
            List<Reward.ReplayCheckpoint> persisted=new ArrayList<>();RewardOptions options=nestedOptions(persisted);
            options.setAsyncReplayOccurrenceId("stable");
            CompletionStage<Void> first=f.reward.giveRewardUserAsync(user,new HashMap<>(),options);drain(f);
            assertThrows(CompletionException.class,()->first.toCompletableFuture().join());
            assertEquals(Arrays.asList("effect","notify"),phases);assertEquals(1,persisted.size());
            Reward.ReplayCheckpoint checkpoint=persisted.get(0);RewardOptions retry=nestedOptions(persisted);
            retry.setAsyncReplayOccurrenceId("stable");retry.setAsyncReplayProgress(checkpoint.getReplayProgress());
            retry.setAsyncReplayRegistryFingerprints(checkpoint.getReplayRegistryFingerprints());
            CompletionStage<Void> resumed=f.reward.giveRewardUserAsync(user,checkpoint.getPlaceholders(),retry);drain(f);resumed.toCompletableFuture().join();
            assertEquals(Arrays.asList("effect","notify","notify","second"),phases);
            assertEquals(2,persisted.size());assertEquals(2,persisted.get(1).getReplayProgress().get("root"));
        });
    }

    @Test void queuedCheckpointCannotPublishAfterAdmissionTimeoutEvenWhenDeadlineCallbackIsDelayed() {
        for(boolean fireDeadline:Arrays.asList(false,true))fixture(f -> {
            java.util.concurrent.atomic.AtomicInteger writes=new java.util.concurrent.atomic.AtomicInteger();
            RewardOptions options=new RewardOptions();options.setAsyncReplayCheckpointConsumer(checkpoint->writes.incrementAndGet());
            Reward.ReplayState state=Reward.replayStateFor(options);
            f.dispatch.primary.set(true);
            CompletionStage<Void> result;
            try {result=state.persistCheckpointAsync(f.dispatch.plugin,new HashMap<>());}
            finally {f.dispatch.primary.set(false);}
            assertFalse(result.toCompletableFuture().isDone());assertEquals(1,f.dispatch.asyncQueued.size());assertEquals(0,writes.get());
            if(fireDeadline)f.dispatch.deadlines.get(0).run();
            else f.dispatch.clock.set(java.util.concurrent.TimeUnit.SECONDS.toNanos(30));
            f.dispatch.runAsyncNext();
            assertInstanceOf(java.util.concurrent.TimeoutException.class,
                    assertThrows(CompletionException.class,()->result.toCompletableFuture().join()).getCause());
            assertEquals(0,writes.get());
        });
    }

    @Test void rootWaitsForEffectAndCheckpointBeforeAdmittingNextInjection() {
        fixture(f -> {
            AdvancedCoreUser user=onlineUser();repeat(f.reward);doReturn("root").when(f.reward).getRewardName();
            List<String> phases=new ArrayList<>();CompletableFuture<Object> physical=new CompletableFuture<>();
            f.injections.add(async("first",p->{phases.add("first");return physical;}));
            f.injections.add(async("second",p->{phases.add("second");return CompletableFuture.completedFuture(null);}));
            RewardOptions options=new RewardOptions().setCheckRepeat(false);options.setAsyncReplayOccurrenceId("occurrence");
            options.setAsyncReplayCheckpointConsumer(checkpoint->{assertFalse(Bukkit.isPrimaryThread());phases.add("write-"+checkpoint.getReplayProgress().get("root"));});
            CompletionStage<Void> result=f.reward.giveRewardUserAsync(user,new HashMap<>(),options);f.dispatch.runUserPreparation();
            assertEquals(Arrays.asList("first"),phases);physical.complete("done");assertEquals(Arrays.asList("first"),phases);
            f.dispatch.runNext();assertEquals(Arrays.asList("first"),phases);assertFalse(result.toCompletableFuture().isDone());
            f.dispatch.runAsyncNext();assertEquals(Arrays.asList("first","write-1"),phases);assertFalse(result.toCompletableFuture().isDone());
            drain(f);result.toCompletableFuture().join();assertEquals(Arrays.asList("first","write-1","second","write-2"),phases);
        });
    }
    @Test void failedCheckpointRetainsCompletedPrefixAndResumeSkipsPhysicalEffect() {
        fixture(f -> {
            AdvancedCoreUser user=onlineUser();repeat(f.reward);doReturn("root").when(f.reward).getRewardName();List<String> effects=new ArrayList<>();
            f.injections.add(async("first",p->{effects.add("first");return CompletableFuture.completedFuture(null);}));
            f.injections.add(async("second",p->{effects.add("second");return CompletableFuture.completedFuture(null);}));
            RewardOptions options=new RewardOptions().setCheckRepeat(false);options.setAsyncReplayOccurrenceId("stable");
            options.setAsyncReplayCheckpointConsumer(checkpoint->{throw new IllegalStateException("checked store unavailable");});
            CompletionStage<Void> result=f.reward.giveRewardUserAsync(user,new HashMap<>(),options);drain(f);
            Reward.RewardReplayFailure failure=assertInstanceOf(Reward.RewardReplayFailure.class,assertThrows(CompletionException.class,()->result.toCompletableFuture().join()).getCause());
            assertEquals(Arrays.asList("first"),effects);assertEquals(1,failure.getReplayProgress().get("root"));
            RewardOptions retry=new RewardOptions().setCheckRepeat(false);retry.setAsyncReplayProgress(failure.getReplayProgress());retry.setAsyncReplayRegistryFingerprints(failure.getReplayRegistryFingerprints());retry.setAsyncReplayOccurrenceId("stable");
            retry.setAsyncReplayCheckpointConsumer(checkpoint->assertFalse(Bukkit.isPrimaryThread()));
            CompletionStage<Void> resumed=f.reward.giveRewardUserAsync(user,failure.getReplayPlaceholders(),retry);drain(f);resumed.toCompletableFuture().join();assertEquals(Arrays.asList("first","second"),effects);
            // Changing the registry must fail before touching another effect.
            f.injections.get(0).postReward();CompletionStage<Void> changed=f.reward.giveRewardUserAsync(user,new HashMap<>(),retry);drain(f);
            assertThrows(CompletionException.class,()->changed.toCompletableFuture().join());assertEquals(Arrays.asList("first","second"),effects);
        });
    }
    @Test void rootCheckpointCannotUseReplacementRuntimeAfterPhysicalEffect() {
        fixture(f -> {
            AdvancedCoreUser user=onlineUser();repeat(f.reward);CompletableFuture<Object> physical=new CompletableFuture<>();List<String> writes=new ArrayList<>();
            f.injections.add(async("first",p->physical));RewardOptions options=new RewardOptions().setCheckRepeat(false);options.setAsyncReplayCheckpointConsumer(checkpoint->writes.add("write"));
            CompletionStage<Void> result=f.reward.giveRewardUserAsync(user,new HashMap<>(),options);f.dispatch.runUserPreparation();
            f.dispatch.owner.close();ServerThreadRewardDispatch replacement=new ServerThreadRewardDispatch(f.dispatch.plugin);when(f.dispatch.plugin.getRewardDispatch()).thenReturn(replacement);
            try {physical.complete("committed");assertThrows(CompletionException.class,()->result.toCompletableFuture().join());assertTrue(writes.isEmpty());assertTrue(f.dispatch.asyncQueued.isEmpty());}finally{replacement.close();}
        });
    }
    @Test void rootContextIsExplicitAndFreshOptionsReuseDoesNotSkipIndependentRecipients() {
        fixture(f -> {
            AdvancedCoreUser user=onlineUser();repeat(f.reward);doReturn("root").when(f.reward).getRewardName();List<String> occurrences=new ArrayList<>();
            f.injections.add(async("first",p->{assertEquals("root/0",Reward.currentReplayKey());assertNotNull(Reward.currentReplayState());occurrences.add(Reward.currentReplayOccurrenceId());assertNotEquals(Reward.currentReplayOccurrenceId(),Reward.currentReplaySideEffectOccurrenceId());return CompletableFuture.completedFuture(null);}));
            RewardOptions shared=new RewardOptions().setCheckRepeat(false);
            CompletionStage<Void> first=f.reward.giveRewardUserAsync(user,new HashMap<>(),shared);drain(f);first.toCompletableFuture().join();
            assertNull(Reward.currentReplayState());assertNull(Reward.currentReplayKey());assertNull(Reward.currentReplayOccurrenceId());
            CompletionStage<Void> second=f.reward.giveRewardUserAsync(user,new HashMap<>(),shared);drain(f);second.toCompletableFuture().join();
            assertEquals(2,occurrences.size());assertNotEquals(occurrences.get(0),occurrences.get(1));assertTrue(shared.getAsyncReplayProgress().isEmpty());assertNull(shared.getAsyncReplayState());
        });
    }
    @Test void queuedRootAndDeferredItemActionsKeepTheirAdmittedInventoryHandler() {
        fixture(f -> {
            AdvancedCoreUser user=scopedUser(f);org.bukkit.entity.Player player=user.getPlayer();
            com.bencodez.advancedcore.api.item.FullInventoryHandler original=mock(com.bencodez.advancedcore.api.item.FullInventoryHandler.class);
            com.bencodez.advancedcore.api.item.FullInventoryHandler replacement=mock(com.bencodez.advancedcore.api.item.FullInventoryHandler.class);
            when(f.dispatch.plugin.getFullInventoryHandler()).thenReturn(original);
            org.bukkit.inventory.ItemStack item=new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND,3);
            when(original.giveItemAsync(player,item)).thenReturn(CompletableFuture.completedFuture(null));
            when(replacement.giveItemAsync(player,item)).thenReturn(CompletableFuture.completedFuture(null));
            CompletableFuture<Object> gate=new CompletableFuture<>();
            f.injections.add(async("deferred-item",p->{AdvancedCoreUser.AsyncActionContext context=user.captureAsyncActionContext();return gate.thenApply(context.wrap(value->{user.giveItems(item);return value;}));}));
            CompletionStage<Void> result=f.reward.giveInjectedRewardsAsync(user,new HashMap<>());
            when(f.dispatch.plugin.getFullInventoryHandler()).thenReturn(replacement);
            f.dispatch.runNext();gate.complete("ready");drain(f);result.toCompletableFuture().join();
            assertEquals(1,mockingDetails(original).getInvocations().size());assertEquals(0,mockingDetails(replacement).getInvocations().size());
        });
    }
    @Test void independentActionScopeKeepsHandlerAcrossItsDeferredExecution() {
        fixture(f -> {
            AdvancedCoreUser user=scopedUser(f);org.bukkit.entity.Player player=user.getPlayer();
            com.bencodez.advancedcore.api.item.FullInventoryHandler original=mock(com.bencodez.advancedcore.api.item.FullInventoryHandler.class);
            com.bencodez.advancedcore.api.item.FullInventoryHandler replacement=mock(com.bencodez.advancedcore.api.item.FullInventoryHandler.class);
            when(f.dispatch.plugin.getFullInventoryHandler()).thenReturn(original);
            org.bukkit.inventory.ItemStack item=new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND,3);
            when(original.giveItemAsync(player,item)).thenReturn(CompletableFuture.completedFuture(null));
            when(replacement.giveItemAsync(player,item)).thenReturn(CompletableFuture.completedFuture(null));
            AdvancedCoreUser.AsyncActionCollection scope=user.beginAsyncActionCollection();user.giveItem(item);
            when(f.dispatch.plugin.getFullInventoryHandler()).thenReturn(replacement);
            user.endAsyncActionCollection(scope).toCompletableFuture().join();assertEquals(1,mockingDetails(original).getInvocations().size());assertEquals(0,mockingDetails(replacement).getInvocations().size());
        });
    }

    @Test void rootNativeActionUsesAdmittedDispatcherWhenPluginGetterHasChanged() {
        fixture(f -> {
            AdvancedCoreUser user=scopedUser(f);org.bukkit.entity.Player player=user.getPlayer();java.util.UUID identity=java.util.UUID.randomUUID();
            doReturn(identity.toString()).when(user).getUUID();when(player.getUniqueId()).thenReturn(identity);when(player.isOnline()).thenReturn(true);when(Bukkit.getPlayer(identity)).thenReturn(player);
            f.injections.add(new RewardInject("exp") {@Override public Object onRewardRequest(Reward reward,AdvancedCoreUser target,ConfigurationSection config,HashMap<String,String> placeholders){target.giveExp(7);return null;}});
            CompletionStage<Void> result=f.reward.giveInjectedRewardsAsync(user,new HashMap<>());
            ServerThreadRewardDispatch replacement=new ServerThreadRewardDispatch(f.dispatch.plugin);replacement.close();when(f.dispatch.plugin.getRewardDispatch()).thenReturn(replacement);
            drain(f);assertDoesNotThrow(()->result.toCompletableFuture().join());verify(player).giveExp(7);
        });
    }

    @Test void completeEntryPointFiresAsyncEventAndEvaluatesRequirementsBeforeEffects() {
        fixture(f -> {
            fullSetup(f);AdvancedCoreUser user=onlineUser();when(user.isOnline()).thenAnswer(ignored->{assertTrue(Bukkit.isPrimaryThread());return true;});repeat(f.reward);
            List<String> phases=new ArrayList<>();CompletableFuture<Object> physical=new CompletableFuture<>();
            org.bukkit.plugin.PluginManager manager=Bukkit.getPluginManager();doAnswer(call->{assertFalse(Bukkit.isPrimaryThread());assertTrue(((org.bukkit.event.Event)call.getArgument(0)).isAsynchronous());phases.add("event");return null;}).when(manager).callEvent(any());
            com.bencodez.advancedcore.api.rewards.injectedrequirement.RequirementInject requirement=new com.bencodez.advancedcore.api.rewards.injectedrequirement.RequirementInject("required") {
                @Override public boolean onRequirementRequest(Reward reward,AdvancedCoreUser target,ConfigurationSection config,RewardOptions options){assertTrue(Bukkit.isPrimaryThread());phases.add("requirement");return true;}
            };
            when(f.dispatch.plugin.getRewardHandler().getInjectedRequirements()).thenReturn(new ArrayList<>(Arrays.asList(requirement)));
            f.injections.add(async("effect",p->{phases.add("effect");assertEquals("before",p.get("input"));return physical;}));
            RewardOptions options=new RewardOptions().setCheckRepeat(false).addPlaceholder("input","before");
            CompletionStage<Void> result=f.reward.giveRewardAsync(user,options);options.addPlaceholder("input","after");drain(f);
            assertEquals(Arrays.asList("event","requirement","effect"),phases);assertFalse(result.toCompletableFuture().isDone());physical.complete(null);drain(f);result.toCompletableFuture().join();
        });
    }
    @Test void cancelledCompleteEntryPointDoesNotRunRequirementsOrEffects() {
        fixture(f -> {
            fullSetup(f);AdvancedCoreUser user=onlineUser();List<String> effects=new ArrayList<>();
            org.bukkit.plugin.PluginManager manager=Bukkit.getPluginManager();
            doAnswer(call->{((com.bencodez.advancedcore.listeners.PlayerRewardEvent)call.getArgument(0)).setCancelled(true);return null;}).when(manager).callEvent(any());
            f.injections.add(async("effect",p->{effects.add("bad");return CompletableFuture.completedFuture(null);}));
            CompletionStage<Void> result=f.reward.giveRewardAsync(user,new RewardOptions());drain(f);result.toCompletableFuture().join();assertTrue(effects.isEmpty());verify(user,never()).isOnline();
        });
    }
    @Test void disabledProcessingIsNoOpForOrdinarySendButFailureForRetainedReplay() {
        fixture(f -> {
            fullSetup(f);when(f.dispatch.plugin.getOptions().isProcessRewards()).thenReturn(false);AdvancedCoreUser user=onlineUser();
            f.reward.giveRewardAsync(user,new RewardOptions()).toCompletableFuture().join();
            RewardOptions replay=new RewardOptions();replay.setAsyncReplayCheckpointConsumer(checkpoint->fail("disabled replay checkpoint"));
            assertThrows(CompletionException.class,()->f.reward.giveRewardAsync(user,replay).toCompletableFuture().join());verifyNoInteractions(Bukkit.getPluginManager());
        });
    }
    @Test void retryableRequirementDefersNormalRecoveryButExplicitForceStillRunsEffects() {
        for(boolean force:new boolean[]{false,true})fixture(f -> {
            fullSetup(f);AdvancedCoreUser user=onlineUser();when(user.isOnline()).thenReturn(true);repeat(f.reward);
            com.bencodez.advancedcore.api.rewards.injectedrequirement.RequirementInject requirement=
                    new com.bencodez.advancedcore.api.rewards.injectedrequirement.RequirementInject("Server") {
                @Override public boolean onRequirementRequest(Reward reward,AdvancedCoreUser target,ConfigurationSection config,RewardOptions options) {
                    assertTrue(Bukkit.isPrimaryThread());return false;
                }
            }.allowReattempt();
            when(f.dispatch.plugin.getRewardHandler().getInjectedRequirements()).thenReturn(new ArrayList<>(Arrays.asList(requirement)));
            List<String> effects=new ArrayList<>();List<Reward.ReplayCheckpoint> checkpoints=new ArrayList<>();
            f.injections.add(async("effect",p->{effects.add("delivered");return CompletableFuture.completedFuture(null);}));
            RewardOptions replay=nestedOptions(checkpoints).setOnline(false).setCheckTimed(false);
            if(force)replay.setGiveOffline(false).forceOffline();
            CompletionStage<Void> result=f.reward.giveRewardAsync(user,replay);drain(f);
            if(force) {result.toCompletableFuture().join();assertEquals(Arrays.asList("delivered"),effects);assertFalse(checkpoints.isEmpty());}
            else {Throwable failure=assertThrows(CompletionException.class,()->result.toCompletableFuture().join());
                assertTrue(Reward.isOfflineReplayDeferred(failure));assertTrue(effects.isEmpty());assertTrue(checkpoints.isEmpty());}
            verify(user,never()).addOfflineRewards(any(),any());
        });
    }

    @Test void pausedDurableReplayRemainsDeferredWithoutAnotherQueueInsertion() {
        fixture(f -> {
            fullSetup(f);when(f.dispatch.plugin.getOptions().isPauseRewards()).thenReturn(true);AdvancedCoreUser user=onlineUser();when(user.isOnline()).thenReturn(true);
            RewardOptions replay=new RewardOptions();replay.setAsyncReplayCheckpointConsumer(checkpoint->fail("paused checkpoint"));
            CompletionStage<Void> result=f.reward.giveRewardAsync(user,replay);drain(f);
            Throwable failure=assertThrows(CompletionException.class,()->result.toCompletableFuture().join());assertTrue(Reward.isOfflineReplayDeferred(failure));verify(user,never()).addOfflineRewards(any(),any());
        });
    }
    @Test void requirementExceptionRetainsDurableOccurrenceInsteadOfAcknowledgingIt() {
        fixture(f -> {
            fullSetup(f);AdvancedCoreUser user=onlineUser();when(user.isOnline()).thenReturn(true);
            com.bencodez.advancedcore.api.rewards.injectedrequirement.RequirementInject requirement=new com.bencodez.advancedcore.api.rewards.injectedrequirement.RequirementInject("broken") {
                @Override public boolean onRequirementRequest(Reward reward,AdvancedCoreUser target,ConfigurationSection config,RewardOptions options){assertTrue(Bukkit.isPrimaryThread());throw new IllegalStateException("requirement unavailable");}
            };
            when(f.dispatch.plugin.getRewardHandler().getInjectedRequirements()).thenReturn(new ArrayList<>(Arrays.asList(requirement)));
            RewardOptions replay=new RewardOptions();replay.setAsyncReplayCheckpointConsumer(checkpoint->fail("failed requirement checkpoint"));
            CompletionStage<Void> result=f.reward.giveRewardAsync(user,replay);drain(f);assertThrows(CompletionException.class,()->result.toCompletableFuture().join());verify(user,never()).addOfflineRewards(any(),any());
        });
    }
    @Test void completeEntryPointFromServerOwnerRejectsRetiredQueuedAsyncEvent() {
        fixture(f -> {
            fullSetup(f);f.dispatch.primary.set(true);CompletionStage<Void> result=f.reward.giveRewardAsync(onlineUser(),new RewardOptions());f.dispatch.primary.set(false);
            assertFalse(result.toCompletableFuture().isDone());f.dispatch.owner.close();drain(f);assertThrows(CompletionException.class,()->result.toCompletableFuture().join());verifyNoInteractions(Bukkit.getPluginManager());
        });
    }
    @Test void voidRewardDispatchUsesCompleteAwaitedPathForOptedInInjection() {
        fixture(f -> {
            fullSetup(f);AdvancedCoreUser user=onlineUser();when(user.isOnline()).thenReturn(true);RepeatHandle repeat=repeat(f.reward);CompletableFuture<Object> physical=new CompletableFuture<>();
            f.injections.add(async("effect",p->physical));
            f.reward.giveReward(user,new RewardOptions());drain(f);verify(repeat,never()).giveRepeat(any(),any());verify(Bukkit.getPluginManager()).callEvent(any());
            physical.complete(null);drain(f);verify(repeat).giveRepeat(f.dispatch.plugin,user);
        });
    }

    @Test void selectedChildWaitsForSelectionCheckpointAndPhysicalCompletion() {
        fixture(f -> {
            AdvancedCoreUser user=onlineUser();List<Reward.ReplayCheckpoint> writes=new ArrayList<>();
            CompletableFuture<Void> child=new CompletableFuture<>();List<String> calls=new ArrayList<>();
            f.injections.add(async("random",p->{
                String selected=Reward.replaySelection(p,()->"child");Reward.ReplayState state=Reward.currentReplayState();String key=Reward.currentReplayKey();
                return Reward.persistReplayMetadataAsync(f.dispatch.plugin,p).thenCompose(unused->
                        Reward.replaySingleNestedReward(f.dispatch.plugin,p,"selected",state,key,()->{
                            assertTrue(writes.stream().anyMatch(w->w.getPlaceholders().keySet().stream().anyMatch(k->k.startsWith("__advancedcore_replay_selection_"))));
                            calls.add(selected);return child;
                        })).thenApply(unused->(Object)selected);
            }));
            RewardOptions options=nestedOptions(writes);CompletionStage<Void> result=f.reward.giveRewardUserAsync(user,new HashMap<>(),options);drain(f);
            assertEquals(Arrays.asList("child"),calls);assertFalse(result.toCompletableFuture().isDone());
            child.complete(null);drain(f);result.toCompletableFuture().join();
            assertTrue(writes.get(writes.size()-1).getPlaceholders().entrySet().stream().anyMatch(e->e.getKey().startsWith("__advancedcore_replay_single_child_") && e.getValue().equals("1")));
        });
    }
    @Test void retryKeepsSelectedBranchAndSkipsDurablyCompletedChildBeforeParentCheckpoint() {
        fixture(f -> {
            AdvancedCoreUser user=onlineUser();List<Reward.ReplayCheckpoint> writes=new ArrayList<>();java.util.concurrent.atomic.AtomicInteger rolls=new java.util.concurrent.atomic.AtomicInteger(),children=new java.util.concurrent.atomic.AtomicInteger();
            f.injections.add(async("random",p->{
                String selected=Reward.replaySelection(p,()->"child-"+rolls.incrementAndGet());Reward.ReplayState state=Reward.currentReplayState();String key=Reward.currentReplayKey();
                return Reward.persistReplayMetadataAsync(f.dispatch.plugin,p).thenCompose(unused->Reward.replaySingleNestedReward(f.dispatch.plugin,p,"selected",state,key,()->{
                    children.incrementAndGet();return CompletableFuture.completedFuture(null);
                })).thenApply(unused->(Object)selected);
            }));
            CompletionStage<Void> first=f.reward.giveRewardUserAsync(user,new HashMap<>(),nestedOptions(writes));drain(f);first.toCompletableFuture().join();
            // Recover the child completion write, before the parent's completed injection count.
            Reward.ReplayCheckpoint checkpoint=writes.stream().filter(w->w.getPlaceholders().keySet().stream().anyMatch(k->k.startsWith("__advancedcore_replay_single_child_"))).findFirst().get();
            RewardOptions retry=nestedOptions(writes);retry.setAsyncReplayProgress(checkpoint.getReplayProgress());retry.setAsyncReplayRegistryFingerprints(checkpoint.getReplayRegistryFingerprints());
            CompletionStage<Void> resumed=f.reward.giveRewardUserAsync(user,checkpoint.getPlaceholders(),retry);drain(f);resumed.toCompletableFuture().join();
            assertEquals(1,rolls.get());assertEquals(1,children.get());
        });
    }
    @Test void failedChildRetainsSelectionWithoutPublishingCompletionMarker() {
        fixture(f -> {
            AdvancedCoreUser user=onlineUser();List<Reward.ReplayCheckpoint> writes=new ArrayList<>();CompletableFuture<Void> child=new CompletableFuture<>();
            f.injections.add(async("random",p->{String selected=Reward.replaySelection(p,()->"child");Reward.ReplayState state=Reward.currentReplayState();String key=Reward.currentReplayKey();
                return Reward.persistReplayMetadataAsync(f.dispatch.plugin,p).thenCompose(unused->Reward.replaySingleNestedReward(f.dispatch.plugin,p,"selected",state,key,()->child)).thenApply(unused->(Object)selected);
            }));
            CompletionStage<Void> result=f.reward.giveRewardUserAsync(user,new HashMap<>(),nestedOptions(writes));drain(f);child.completeExceptionally(new IllegalStateException("child failed"));drain(f);
            assertThrows(CompletionException.class,()->result.toCompletableFuture().join());assertFalse(writes.isEmpty());
            assertTrue(writes.stream().allMatch(w->w.getPlaceholders().keySet().stream().noneMatch(k->k.startsWith("__advancedcore_replay_single_child_"))));
        });
    }
    @Test void explicitChildrenCarryDistinctPathsAndCapturedOwnerAcrossContinuation() {
        fixture(f -> {
            RewardOptions root=nestedOptions(new ArrayList<>());Reward.ReplayState state=Reward.replayStateFor(root);state.captureRuntime(f.dispatch.plugin);
            RewardOptions one=Reward.withReplayState(new RewardOptions(),state,"root/0","selected:one","occurrence");
            RewardOptions two=Reward.withReplayState(new RewardOptions(),state,"root/1","selected:one","occurrence");
            assertNotEquals(one.getAsyncReplayKey(),two.getAsyncReplayKey());assertEquals("occurrence",one.getAsyncReplayOccurrenceId());assertEquals("occurrence",two.getAsyncReplayOccurrenceId());
            ServerThreadRewardDispatch replacement=new ServerThreadRewardDispatch(f.dispatch.plugin);when(f.dispatch.plugin.getRewardDispatch()).thenReturn(replacement);
            try{assertSame(f.dispatch.owner,Reward.replayStateFor(one).getActionDispatchOwner());}finally{replacement.close();}
        });
    }
    @Test void malformedSelectedChildCompletionFailsBeforeDispatch() {
        fixture(f -> {
            RewardOptions options=nestedOptions(new ArrayList<>());Reward.ReplayState state=Reward.replayStateFor(options);state.captureRuntime(f.dispatch.plugin);
            java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();HashMap<String,String> metadata=new HashMap<>();
            Reward.replaySingleNestedReward(f.dispatch.plugin,metadata,"selected",state,"root",()->{calls.incrementAndGet();return CompletableFuture.completedFuture(null);}).toCompletableFuture().join();
            String key=metadata.keySet().iterator().next();metadata.put(key,"bogus");
            assertThrows(CompletionException.class,()->Reward.replaySingleNestedReward(f.dispatch.plugin,metadata,"selected",state,"root",()->{calls.incrementAndGet();return CompletableFuture.completedFuture(null);}).toCompletableFuture().join());assertEquals(1,calls.get());
        });
    }
    @Test void namedSlashCommandWaitsForActualOwnerInvocationAndPreservesSubstitution() {
        fixture(f -> {
            RewardHandler handler=mock(RewardHandler.class,CALLS_REAL_METHODS);handler.plugin=f.dispatch.plugin;AdvancedCoreUser user=onlineUser();
            org.bukkit.Server server=mock(org.bukkit.Server.class);org.bukkit.command.ConsoleCommandSender console=mock(org.bukkit.command.ConsoleCommandSender.class);
            when(Bukkit.getServer()).thenReturn(server);when(Bukkit.getConsoleSender()).thenReturn(console);
            when(server.dispatchCommand(console,"say token")).thenAnswer(call->{assertTrue(Bukkit.isPrimaryThread());return true;});
            CompletionStage<Void> result=handler.giveRewardAsync(user,"/say %value%",new RewardOptions().addPlaceholder("value","token"));
            assertFalse(result.toCompletableFuture().isDone());verifyNoInteractions(server);drain(f);result.toCompletableFuture().join();verify(server).dispatchCommand(console,"say token");
        });
    }
    @Test void namedSlashCommandUsesCapturedRuntimeAndCannotEscapeRetiredOwner() {
        fixture(f -> {
            RewardHandler handler=mock(RewardHandler.class,CALLS_REAL_METHODS);handler.plugin=f.dispatch.plugin;AdvancedCoreUser user=onlineUser();
            RewardOptions options=nestedOptions(new ArrayList<>());Reward.ReplayState state=Reward.replayStateFor(options);state.captureRuntime(f.dispatch.plugin);options.setAsyncReplayState(state);
            org.bukkit.Server server=mock(org.bukkit.Server.class);when(Bukkit.getServer()).thenReturn(server);
            f.dispatch.owner.close();ServerThreadRewardDispatch replacement=new ServerThreadRewardDispatch(f.dispatch.plugin);when(f.dispatch.plugin.getRewardDispatch()).thenReturn(replacement);
            try{CompletionStage<Void> result=handler.giveRewardAsync(user,"/say token",options);assertThrows(CompletionException.class,()->result.toCompletableFuture().join());verifyNoInteractions(server);assertTrue(f.dispatch.queued.isEmpty());}finally{replacement.close();}
        });
    }

    private RewardOptions nestedOptions(List<Reward.ReplayCheckpoint> writes) {
        RewardOptions options=new RewardOptions().setCheckRepeat(false);options.setAsyncReplayKey("root");options.setAsyncReplayOccurrenceId("occurrence");options.setAsyncReplayCheckpointConsumer(writes::add);return options;
    }

    private void fullSetup(Fixture f) {
        com.bencodez.advancedcore.AdvancedCoreConfigOptions config=mock(com.bencodez.advancedcore.AdvancedCoreConfigOptions.class);when(f.dispatch.plugin.getOptions()).thenReturn(config);
        when(config.isProcessRewards()).thenReturn(true);when(config.getFormatRewardTimeFormat()).thenReturn("yyyy-MM-dd");
        when(f.dispatch.plugin.getRewardHandler().getInjectedRequirements()).thenReturn(new ArrayList<>());
        when(Bukkit.getPluginManager()).thenReturn(mock(org.bukkit.plugin.PluginManager.class));
    }

    private void drain(Fixture f) {
        int iterations=0;
        while(!f.dispatch.queued.isEmpty() || !f.dispatch.asyncQueued.isEmpty()) {
            assertTrue(iterations++<100,"pipeline did not settle");
            if(!f.dispatch.queued.isEmpty())f.dispatch.runNext();else f.dispatch.runAsyncNext();
        }
    }

    private Reward.ReplayState replayState() {
        try {java.lang.reflect.Constructor<Reward.ReplayState> constructor=Reward.ReplayState.class.getDeclaredConstructor(java.util.Map.class);constructor.setAccessible(true);return constructor.newInstance((Object)null);}catch(Exception failure){throw new AssertionError(failure);}
    }
    private AdvancedCoreUser scopedUser(Fixture f) {
        AdvancedCoreUser user=mock(AdvancedCoreUser.class,CALLS_REAL_METHODS);doReturn(mock(org.bukkit.entity.Player.class)).when(user).getPlayer();
        try {java.lang.reflect.Field field=AdvancedCoreUser.class.getDeclaredField("plugin");field.setAccessible(true);field.set(user,f.dispatch.plugin);}catch(Exception failure){throw new AssertionError(failure);}return user;
    }

    private AdvancedCoreUser onlineUser() {
        AdvancedCoreUser user=mock(AdvancedCoreUser.class);org.bukkit.entity.Player player=mock(org.bukkit.entity.Player.class);
        when(user.getPlayer()).thenReturn(player);when(user.getPlayerName()).thenReturn("User");when(user.getUUID()).thenReturn("uuid");when(player.getDisplayName()).thenReturn("Display");return user;
    }
    private RepeatHandle repeat(Reward reward) {
        RepeatHandle repeat=mock(RepeatHandle.class);when(repeat.isEnabled()).thenReturn(true);
        try {java.lang.reflect.Field field=Reward.class.getDeclaredField("repeatHandle");field.setAccessible(true);field.set(reward,repeat);}
        catch(ReflectiveOperationException failure){throw new AssertionError(failure);}return repeat;
    }
    private RewardInject async(String name,java.util.function.Function<HashMap<String,String>,CompletionStage<Object>> callback) {
        return new RewardInject(name) {
            @Override public boolean supportsAsyncRequest(){return true;}
            @Override public Object onRewardRequest(Reward r,AdvancedCoreUser u,ConfigurationSection data,HashMap<String,String> p){throw new AssertionError("async must not use sync callback");}
            @Override public CompletionStage<Object> onRewardRequestAsync(Reward r,AdvancedCoreUser u,ConfigurationSection data,HashMap<String,String> p){assertTrue(Bukkit.isPrimaryThread());return callback.apply(p);}
        };
    }
    private void fixture(Consumer<Fixture> test) {
        LegacyRewardDispatchTest.Fixture dispatch=new LegacyRewardDispatchTest.Fixture();
        when(dispatch.plugin.getDataFolder()).thenReturn(new File(System.getProperty("java.io.tmpdir"),"legacy-reward-dispatch"));
        when(dispatch.plugin.getRewardDispatch()).thenReturn(dispatch.owner);
        try(MockedStatic<Bukkit> bukkit=mockStatic(Bukkit.class);MockedStatic<AdvancedCorePlugin> global=mockStatic(AdvancedCorePlugin.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenAnswer(ignored->dispatch.primary.get());bukkit.when(Bukkit::getScheduler).thenReturn(dispatch.scheduler);
            global.when(AdvancedCorePlugin::getInstance).thenReturn(dispatch.plugin);
            RewardHandler handler=mock(RewardHandler.class);when(dispatch.plugin.getRewardHandler()).thenReturn(handler);
            ArrayList<RewardInject> injections=new ArrayList<>();when(handler.getInjectedRewards()).thenReturn(injections);when(handler.getPlaceholders()).thenReturn(new ArrayList<>());
            Reward reward=mock(Reward.class,CALLS_REAL_METHODS);reward.plugin=dispatch.plugin;
            RewardFileData data=mock(RewardFileData.class);when(reward.getConfig()).thenReturn(data);when(data.getConfigData()).thenReturn(new YamlConfiguration());
            try{test.accept(new Fixture(dispatch,reward,injections));}finally{dispatch.owner.close();RewardHandler.getInstance().getRepeatTimer().cancel();}
        }
    }
    static class Fixture {
        final LegacyRewardDispatchTest.Fixture dispatch;final Reward reward;final ArrayList<RewardInject> injections;
        Fixture(LegacyRewardDispatchTest.Fixture dispatch,Reward reward,ArrayList<RewardInject> injections){this.dispatch=dispatch;this.reward=reward;this.injections=injections;}
    }
}

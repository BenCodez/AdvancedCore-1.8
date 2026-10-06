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

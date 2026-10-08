package com.bencodez.advancedcore.api.rewards;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.rewards.injected.RewardInjectInt;
import com.bencodez.advancedcore.api.user.AdvancedCoreUser;

class LegacyAsyncIntegerInjectTest {
    @Test void legacyBridgePreservesConfiguredValueAndFallbackPlaceholder() {
        withPlugin(reward -> {
            RewardInjectInt inject = legacy(value -> null);
            YamlConfiguration config = new YamlConfiguration(); config.set("Points", 7);
            assertEquals("7", invoke(inject, reward, config).toCompletableFuture().join());
            assertFalse(inject.supportsAsyncRequest());
        });
    }
    @Test void missingDataSkipsCallbackWhileForcedDataUsesLegacyDefault() {
        withPlugin(reward -> {
            java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
            RewardInjectInt inject = legacy(value -> { calls.incrementAndGet(); return "value=" + value; });
            inject.setDefaultValue(9); YamlConfiguration config = new YamlConfiguration();
            assertNull(invoke(inject, reward, config).toCompletableFuture().join()); assertEquals(0, calls.get());
            inject.alwaysForce(); config.set("Points", "not-an-integer");
            assertEquals("value=9", invoke(inject, reward, config).toCompletableFuture().join());
            config.set("Points", null); inject.alwaysForceNoData();
            assertEquals("value=9", invoke(inject, reward, config).toCompletableFuture().join()); assertEquals(2, calls.get());
        });
    }
    @Test void typedHookWaitsForPhysicalCompletionAndPreservesContext() {
        withPlugin(reward -> {
            CompletableFuture<String> physical = new CompletableFuture<>(); HashMap<String,String> placeholders = new HashMap<>();
            AdvancedCoreUser user = mock(AdvancedCoreUser.class);
            RewardInjectInt inject = new RewardInjectInt("Points") {
                @Override public boolean supportsAsyncRequest() { return true; }
                @Override public String onRewardRequest(Reward r, AdvancedCoreUser u, int value, HashMap<String,String> p) {
                    throw new AssertionError("synchronous callback must not run");
                }
                @Override public CompletionStage<String> onRewardRequestAsync(Reward r, AdvancedCoreUser u, int value, HashMap<String,String> p) {
                    assertSame(reward,r); assertSame(user,u); assertSame(placeholders,p); assertEquals(12,value); return physical;
                }
            };
            YamlConfiguration config = new YamlConfiguration(); config.set("Points",12);
            CompletionStage<Object> result = inject.onRewardRequestAsync(reward,user,config,placeholders);
            assertFalse(result.toCompletableFuture().isDone()); physical.complete("committed");
            assertEquals("committed",result.toCompletableFuture().join());
        });
    }
    @Test void asynchronousFailurePropagatesWithoutFallbackSuccess() {
        withPlugin(reward -> {
            CompletableFuture<String> physical = new CompletableFuture<>();
            RewardInjectInt inject = async(physical); YamlConfiguration config = new YamlConfiguration(); config.set("Points",1);
            CompletionStage<Object> result = invoke(inject,reward,config); IllegalStateException failure = new IllegalStateException("storage failed");
            physical.completeExceptionally(failure);
            assertSame(failure,assertThrows(CompletionException.class,()->result.toCompletableFuture().join()).getCause());
        });
    }
    @Test void synchronousThrowAndNullStageAreExceptionalResults() {
        withPlugin(reward -> {
            YamlConfiguration config = new YamlConfiguration(); config.set("Points",1);
            IllegalArgumentException failure = new IllegalArgumentException("rejected");
            RewardInjectInt throwsFailure = legacy(value -> {throw failure;});
            assertSame(failure,assertThrows(CompletionException.class,()->invoke(throwsFailure,reward,config).toCompletableFuture().join()).getCause());
            assertInstanceOf(IllegalStateException.class,assertThrows(CompletionException.class,
                ()->invoke(async(null),reward,config).toCompletableFuture().join()).getCause());
        });
    }
    private RewardInjectInt legacy(java.util.function.IntFunction<String> callback) {
        return new RewardInjectInt("Points") {
            @Override public String onRewardRequest(Reward r, AdvancedCoreUser u, int value, HashMap<String,String> p) { return callback.apply(value); }
        };
    }
    private RewardInjectInt async(CompletionStage<String> result) {
        return new RewardInjectInt("Points") {
            @Override public String onRewardRequest(Reward r, AdvancedCoreUser u, int value, HashMap<String,String> p) { throw new AssertionError(); }
            @Override public CompletionStage<String> onRewardRequestAsync(Reward r, AdvancedCoreUser u, int value, HashMap<String,String> p) { return result; }
        };
    }
    private CompletionStage<Object> invoke(RewardInjectInt inject, Reward reward, YamlConfiguration config) {
        return inject.onRewardRequestAsync(reward,null,config,new HashMap<>());
    }
    private void withPlugin(Consumer<Reward> test) {
        try(MockedStatic<AdvancedCorePlugin> global = mockStatic(AdvancedCorePlugin.class)) {
            global.when(AdvancedCorePlugin::getInstance).thenReturn(mock(AdvancedCorePlugin.class));
            Reward reward = mock(Reward.class); when(reward.getRewardName()).thenReturn("TestReward"); test.accept(reward);
        }
    }
}

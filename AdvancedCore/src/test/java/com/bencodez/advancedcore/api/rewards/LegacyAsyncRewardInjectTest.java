package com.bencodez.advancedcore.api.rewards;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.bukkit.configuration.ConfigurationSection;
import com.bencodez.advancedcore.api.rewards.injected.RewardInject;
import com.bencodez.advancedcore.api.user.AdvancedCoreUser;

class LegacyAsyncRewardInjectTest {
    @Test void legacyDefaultsAndBridgePreserveSynchronousResult() {
        RewardInject inject=inject(()->"legacy");assertFalse(inject.supportsAsyncRequest());
        assertFalse(inject.requiresConfiguredDataForAsync());assertFalse(inject.hasPendingReplayWork(new HashMap<>()));
        assertTrue(inject.supportsAsyncSynchronization());
        assertEquals("legacy",inject.onRewardRequestAsync(null,null,null,new HashMap<>()).toCompletableFuture().join());
        assertNull(inject.onReplayCheckpointPersisted(null,null,"occurrence","key").toCompletableFuture().join());
    }
    @Test void bridgeExposesOriginalFailureAsExceptionalCompletion() {
        IllegalStateException failure=new IllegalStateException("failed");RewardInject inject=inject(()->{throw failure;});
        assertSame(failure,assertThrows(CompletionException.class,
            ()->inject.onRewardRequestAsync(null,null,null,new HashMap<>()).toCompletableFuture().join()).getCause());
    }
    @Test void serializationWaitsForStageSettlementNotJustInvocation() {
        RewardInject inject=inject(()->null);CompletableFuture<Object> first=new CompletableFuture<>();List<String> calls=new ArrayList<>();
        CompletionStage<Object> a=inject.runSynchronizedAsync(()->{calls.add("first");return first;});
        CompletionStage<Object> b=inject.runSynchronizedAsync(()->{calls.add("second");return CompletableFuture.completedFuture("two");});
        assertEquals(Collections.singletonList("first"),calls);assertFalse(b.toCompletableFuture().isDone());
        first.complete("one");assertEquals("one",a.toCompletableFuture().join());assertEquals("two",b.toCompletableFuture().join());
        assertEquals(Arrays.asList("first","second"),calls);
    }
    @Test void failedStageDoesNotPoisonLaterRequests() {
        RewardInject inject=inject(()->null);CompletableFuture<Object> first=new CompletableFuture<>();
        CompletionStage<Object> a=inject.runSynchronizedAsync(()->first);
        CompletionStage<Object> b=inject.runSynchronizedAsync(()->CompletableFuture.completedFuture("next"));
        first.completeExceptionally(new IllegalStateException("offline"));assertThrows(CompletionException.class,()->a.toCompletableFuture().join());
        assertEquals("next",b.toCompletableFuture().join());
    }
    @Test void rejectedFactoryAndNullStageReleaseSerializedAdmission() {
        RewardInject inject=inject(()->null);
        assertThrows(CompletionException.class,()->inject.runSynchronizedAsync(()->{throw new RejectedExecutionException("stopped");}).toCompletableFuture().join());
        assertThrows(CompletionException.class,()->inject.runSynchronizedAsync(()->null).toCompletableFuture().join());
        assertEquals("next",inject.runSynchronizedAsync(()->CompletableFuture.completedFuture("next")).toCompletableFuture().join());
    }
    @Test void requestFactoryCanAwaitAnotherThreadsSubmissionWithoutHoldingMonitor() throws Exception {
        RewardInject inject=inject(()->null);ExecutorService worker=Executors.newSingleThreadExecutor();CompletableFuture<Object> first=new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicReference<CompletionStage<Object>> next=new java.util.concurrent.atomic.AtomicReference<>();
        try {
            CompletionStage<Object> result=inject.runSynchronizedAsync(()->{
                try {next.set(worker.submit(()->inject.runSynchronizedAsync(()->CompletableFuture.completedFuture("next"))).get(5,TimeUnit.SECONDS));}
                catch(Exception failure){throw new AssertionError(failure);}return first;
            });
            assertFalse(next.get().toCompletableFuture().isDone());first.complete("first");
            assertEquals("first",result.toCompletableFuture().join());assertEquals("next",next.get().toCompletableFuture().join());
        }finally {worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void cancellingObserverDoesNotReleaseAnUnfinishedInjection() {
        RewardInject inject=inject(()->null);CompletableFuture<Object> work=new CompletableFuture<>();
        CompletableFuture<Object> observer=inject.runSynchronizedAsync(()->work).toCompletableFuture();
        CompletionStage<Object> next=inject.runSynchronizedAsync(()->CompletableFuture.completedFuture("next"));
        assertTrue(observer.cancel(false));assertFalse(work.isDone());assertFalse(next.toCompletableFuture().isDone());
        work.complete("done");assertEquals("next",next.toCompletableFuture().join());
    }
    private RewardInject inject(java.util.function.Supplier<Object> callback) {
        return new RewardInject("Test") {
            @Override public Object onRewardRequest(Reward reward,AdvancedCoreUser user,ConfigurationSection data,HashMap<String,String> placeholders) {
                return callback.get();
            }
        };
    }
}

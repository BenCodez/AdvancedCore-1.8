package com.bencodez.advancedcore.api.rewards;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import com.bencodez.advancedcore.AdvancedCorePlugin;

class LegacyRewardDispatchTest {
    @Test void queuedAdmissionIsNotCompletionAndDeadlineCannotExpireAdmittedWork() {
        fixture(f -> {
            CompletableFuture<String> physical = new CompletableFuture<>(); AtomicInteger effects = new AtomicInteger();
            CompletionStage<String> result = f.owner.dispatch(() -> {effects.incrementAndGet();return physical;},30);
            assertFalse(result.toCompletableFuture().isDone()); assertEquals(0,effects.get());
            assertFalse(result.toCompletableFuture().cancel(false));
            f.runNext(); assertEquals(1,effects.get());assertFalse(result.toCompletableFuture().isDone());
            f.deadlines.get(0).run(); assertFalse(result.toCompletableFuture().isDone());
            f.owner.close(); assertFalse(result.toCompletableFuture().isDone());
            physical.complete("committed");assertEquals("committed",result.toCompletableFuture().join());
            verify(f.deadline).cancel(false);
        });
    }
    @Test void deadlineFencesLateBukkitCallbackBeforeAnySideEffect() {
        fixture(f -> {
            AtomicInteger effects = new AtomicInteger();
            CompletionStage<String> result = f.owner.dispatch(() -> {effects.incrementAndGet();return CompletableFuture.completedFuture("bad");},30);
            f.deadlines.get(0).run(); assertInstanceOf(TimeoutException.class,failure(result));
            f.runNext(); assertEquals(0,effects.get());
        });
    }
    @Test void closeRejectsQueuedWorkAndCallbackCannotReenterIt() {
        fixture(f -> {
            AtomicInteger effects = new AtomicInteger();
            CompletionStage<String> result = f.owner.dispatch(() -> {effects.incrementAndGet();return CompletableFuture.completedFuture("bad");},30);
            f.owner.close();assertInstanceOf(IllegalStateException.class,failure(result));f.runNext();assertEquals(0,effects.get());
            assertInstanceOf(IllegalStateException.class,failure(f.owner.dispatch(()->CompletableFuture.completedFuture("bad"),30)));
        });
    }
    @Test void pluginDisableBeforeCallbackRejectsWithoutSideEffect() {
        fixture(f -> {
            AtomicInteger effects = new AtomicInteger();
            CompletionStage<String> result = f.owner.dispatch(() -> {effects.incrementAndGet();return CompletableFuture.completedFuture("bad");},30);
            when(f.plugin.isEnabled()).thenReturn(false);f.runNext();assertInstanceOf(IllegalStateException.class,failure(result));assertEquals(0,effects.get());
        });
    }
    @Test void schedulerAndTimerRejectionPropagateAndFenceLaterCallbacks() {
        fixture(f -> {
            RejectedExecutionException rejected = new RejectedExecutionException("stopped");
            doThrow(rejected).when(f.scheduler).runTask(eq(f.plugin),any(Runnable.class));
            assertSame(rejected,failure(f.owner.dispatch(()->CompletableFuture.completedFuture("bad"),30)));
            f.deadlines.get(0).run();verify(f.deadline).cancel(false);
        });
        fixture(f -> {
            RejectedExecutionException rejected = new RejectedExecutionException("timer stopped");
            when(f.timer.schedule(any(Runnable.class),anyLong(),eq(TimeUnit.MILLISECONDS))).thenThrow(rejected);
            assertSame(rejected,failure(f.owner.dispatch(()->CompletableFuture.completedFuture("bad"),30)));
            assertTrue(f.queued.isEmpty());
        });
    }
    @Test void primaryThreadRunsInlineAndPhysicalFailuresStayExceptional() {
        fixture(f -> {
            f.primary.set(true);CompletableFuture<String> physical = new CompletableFuture<>();
            CompletionStage<String> result = f.owner.dispatch(()->physical,30);assertTrue(f.queued.isEmpty());assertTrue(f.deadlines.isEmpty());
            IllegalStateException rejected = new IllegalStateException("storage failed");physical.completeExceptionally(rejected);assertSame(rejected,failure(result));
            assertInstanceOf(IllegalStateException.class,failure(f.owner.dispatch(()->null,30)));
            assertSame(rejected,failure(f.owner.dispatch(()->{throw rejected;},30)));
        });
    }
    @Test void delayedTimerCannotAdmitCallbackAfterItsMonotonicDeadline() {
        fixture(f -> {
            AtomicInteger effects=new AtomicInteger();
            CompletionStage<String> result=f.owner.dispatch(()->{effects.incrementAndGet();return CompletableFuture.completedFuture("bad");},30);
            // Do not fire the timer: simulate it being occupied by another legacy background task.
            f.clock.set(TimeUnit.MILLISECONDS.toNanos(30));f.runNext();
            assertInstanceOf(TimeoutException.class,failure(result));assertEquals(0,effects.get());
        });
    }
    @Test void offPrimaryDispatchQueuesFromMainAndWaitsForPhysicalStage() {
        fixture(f -> {
            f.primary.set(true);CompletableFuture<String> physical=new CompletableFuture<>();AtomicInteger calls=new AtomicInteger();
            CompletionStage<String> result=f.owner.dispatchOffPrimary(()->{assertFalse(Bukkit.isPrimaryThread());calls.incrementAndGet();return physical;},30);
            assertEquals(0,calls.get());assertEquals(1,f.asyncQueued.size());assertTrue(f.queued.isEmpty());
            f.runAsyncNext();assertEquals(1,calls.get());f.deadlines.get(0).run();f.owner.close();assertFalse(result.toCompletableFuture().isDone());
            physical.complete("committed");assertEquals("committed",result.toCompletableFuture().join());
        });
    }
    @Test void offPrimaryDispatchRunsInlineWhenAlreadyAwayFromOwner() {
        fixture(f -> {
            CompletionStage<String> result=f.owner.dispatchOffPrimary(()->{assertFalse(Bukkit.isPrimaryThread());return CompletableFuture.completedFuture("ready");},30);
            assertEquals("ready",result.toCompletableFuture().join());assertTrue(f.queued.isEmpty());assertTrue(f.asyncQueued.isEmpty());assertTrue(f.deadlines.isEmpty());
        });
    }
    @Test void offPrimaryQueuedCallbacksRespectDeadlineCloseAndSchedulerRejection() {
        fixture(f -> {
            f.primary.set(true);AtomicInteger effects=new AtomicInteger();
            CompletionStage<String> result=f.owner.dispatchOffPrimary(()->{effects.incrementAndGet();return CompletableFuture.completedFuture("bad");},30);
            f.clock.set(TimeUnit.MILLISECONDS.toNanos(30));f.runAsyncNext();assertInstanceOf(TimeoutException.class,failure(result));assertEquals(0,effects.get());
        });
        fixture(f -> {
            f.primary.set(true);AtomicInteger effects=new AtomicInteger();
            CompletionStage<String> result=f.owner.dispatchOffPrimary(()->{effects.incrementAndGet();return CompletableFuture.completedFuture("bad");},30);
            f.owner.close();f.runAsyncNext();assertInstanceOf(IllegalStateException.class,failure(result));assertEquals(0,effects.get());
        });
        fixture(f -> {
            f.primary.set(true);RejectedExecutionException rejected=new RejectedExecutionException("async scheduler stopped");
            doThrow(rejected).when(f.scheduler).runTaskAsynchronously(eq(f.plugin),any(Runnable.class));
            assertSame(rejected,failure(f.owner.dispatchOffPrimary(()->CompletableFuture.completedFuture("bad"),30)));
        });
    }
    @Test void zeroTickAdmissionQueuesEvenOnPrimaryAndAwaitsPhysicalResult() {
        fixture(f->{
            f.primary.set(true);CompletableFuture<String> physical=new CompletableFuture<>();AtomicInteger effects=new AtomicInteger();
            CompletionStage<String> result=f.owner.dispatchAfterTicks(()->{effects.incrementAndGet();return physical;},0,30);assertEquals(0,effects.get());assertEquals(1,f.queued.size());assertFalse(result.toCompletableFuture().isDone());
            f.runNext();assertEquals(1,effects.get());assertFalse(result.toCompletableFuture().isDone());f.deadlines.get(0).run();assertFalse(result.toCompletableFuture().isDone());physical.complete("done");assertEquals("done",result.toCompletableFuture().join());
        });
    }
    @Test void delayedAdmissionUsesExactBukkitTicksAndRetiredOwnerFencesLateCallback() {
        fixture(f->{
            AtomicInteger effects=new AtomicInteger();CompletionStage<String> result=f.owner.dispatchAfterTicks(()->{effects.incrementAndGet();return CompletableFuture.completedFuture("bad");},7,30);
            verify(f.scheduler).runTaskLater(eq(f.plugin),any(Runnable.class),eq(7L));verify(f.scheduler,never()).runTask(eq(f.plugin),any(Runnable.class));assertFalse(result.toCompletableFuture().isDone());f.owner.close();assertInstanceOf(IllegalStateException.class,failure(result));f.runNext();assertEquals(0,effects.get());
        });
    }
    @Test void delayedAdmissionDeadlineFencesCallbackAndRejectsNegativeTicks() {
        fixture(f->{
            AtomicInteger effects=new AtomicInteger();CompletionStage<String> result=f.owner.dispatchAfterTicks(()->{effects.incrementAndGet();return CompletableFuture.completedFuture("bad");},7,30);f.deadlines.get(0).run();assertInstanceOf(TimeoutException.class,failure(result));f.runNext();assertEquals(0,effects.get());
            assertThrows(IllegalArgumentException.class,()->f.owner.dispatchAfterTicks(()->CompletableFuture.completedFuture("bad"),-1,30));
        });
    }
    private Throwable failure(CompletionStage<?> result) {return assertThrows(CompletionException.class,()->result.toCompletableFuture().join()).getCause();}
    private void fixture(Consumer<Fixture> test) {
        try(MockedStatic<Bukkit> bukkit=mockStatic(Bukkit.class)) {
            Fixture f=new Fixture();bukkit.when(Bukkit::isPrimaryThread).thenAnswer(ignored->f.primary.get());
            bukkit.when(Bukkit::getScheduler).thenReturn(f.scheduler);test.accept(f);f.owner.close();
        }
    }
    static class Fixture {
        final AdvancedCorePlugin plugin=mock(AdvancedCorePlugin.class);
        final BukkitScheduler scheduler=mock(BukkitScheduler.class);
        final ScheduledExecutorService timer=mock(ScheduledExecutorService.class);
        final ScheduledFuture<?> deadline=mock(ScheduledFuture.class);
        final List<Runnable> queued=new ArrayList<>(),asyncQueued=new ArrayList<>(),deadlines=new ArrayList<>();final AtomicBoolean primary=new AtomicBoolean();
        final java.util.concurrent.atomic.AtomicLong clock=new java.util.concurrent.atomic.AtomicLong();
        final ServerThreadRewardDispatch owner=new ServerThreadRewardDispatch(plugin,clock::get);
        Fixture() {
            when(plugin.isEnabled()).thenReturn(true);when(plugin.getTimer()).thenReturn(timer);
            when(timer.schedule(any(Runnable.class),anyLong(),eq(TimeUnit.MILLISECONDS))).thenAnswer(call->{deadlines.add(call.getArgument(0));return deadline;});
            when(scheduler.runTask(eq(plugin),any(Runnable.class))).thenAnswer(call->{queued.add(call.getArgument(1));return null;});
            when(scheduler.runTaskLater(eq(plugin),any(Runnable.class),anyLong())).thenAnswer(call->{queued.add(call.getArgument(1));return null;});
            when(scheduler.runTaskAsynchronously(eq(plugin),any(Runnable.class))).thenAnswer(call->{asyncQueued.add(call.getArgument(1));return null;});
        }
        void runNext(){primary.set(true);try{queued.remove(0).run();}finally{primary.set(false);}}
        void runAsyncNext(){primary.set(false);asyncQueued.remove(0).run();}
        void runUserPreparation(){runNext();runAsyncNext();runNext();}
    }
}

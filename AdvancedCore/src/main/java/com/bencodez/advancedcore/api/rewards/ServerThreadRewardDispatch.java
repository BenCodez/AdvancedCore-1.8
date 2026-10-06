package com.bencodez.advancedcore.api.rewards;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.function.LongSupplier;
import org.bukkit.Bukkit;
import com.bencodez.advancedcore.AdvancedCorePlugin;

/** Java 8 scheduler admission receipts. An admitted effect is never timed out as unexecuted. */
public final class ServerThreadRewardDispatch {
    private final AdvancedCorePlugin plugin;
    private final LongSupplier clock;
    private final Set<Request<?>> pending = Collections.newSetFromMap(new IdentityHashMap<Request<?>, Boolean>());
    private boolean closed;

    public ServerThreadRewardDispatch(AdvancedCorePlugin plugin) {
        this(plugin, System::nanoTime);
    }

    ServerThreadRewardDispatch(AdvancedCorePlugin plugin, LongSupplier clock) {
        this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    public <T> CompletionStage<T> dispatch(Supplier<CompletionStage<T>> action, long timeoutMillis) {
        return dispatch(action, timeoutMillis, true);
    }

    /** Run event/storage preparation away from the Bukkit owner using the same admission generation. */
    public <T> CompletionStage<T> dispatchOffPrimary(Supplier<CompletionStage<T>> action, long timeoutMillis) {
        return dispatch(action, timeoutMillis, false);
    }

    private <T> CompletionStage<T> dispatch(Supplier<CompletionStage<T>> action, long timeoutMillis,
            boolean primaryTarget) {
        java.util.Objects.requireNonNull(action, "action");
        if (timeoutMillis <= 0) throw new IllegalArgumentException("Dispatch timeout must be positive");
        Request<T> request = new Request<>(action, timeoutMillis);
        boolean rejected;
        synchronized (this) {
            rejected = closed || !plugin.isEnabled();
            if (!rejected) pending.add(request);
        }
        if (rejected) { request.fail(new IllegalStateException("Reward dispatcher is disabled")); return request.result; }
        try {
            if (Bukkit.isPrimaryThread() == primaryTarget) request.run();
            else {
                request.waitingForScheduler = true;
                ScheduledFuture<?> deadline = plugin.getTimer().schedule(
                    () -> reject(request, new TimeoutException("Reward scheduler admission timed out")),
                    timeoutMillis, TimeUnit.MILLISECONDS);
                request.setDeadline(deadline);
                // Scheduling is admission only. Request.run claims ownership before any side effect.
                if (primaryTarget) Bukkit.getScheduler().runTask(plugin, request::run);
                else Bukkit.getScheduler().runTaskAsynchronously(plugin, request::run);
            }
        } catch (Throwable failure) { reject(request, failure); }
        return request.result;
    }

    /** Reject queued work before the plugin cancels Bukkit tasks or shuts down its existing timer. */
    public void close() {
        ArrayList<Request<?>> rejected;
        synchronized (this) {
            if (closed) return;
            closed = true;
            rejected = new ArrayList<>(pending);
            pending.clear();
        }
        for (Request<?> request : rejected) {
            request.cancelDeadline();
            request.fail(new IllegalStateException("Plugin disabled before reward scheduler admission"));
        }
    }

    private void reject(Request<?> request, Throwable failure) {
        boolean removed;
        synchronized (this) { removed = pending.remove(request); }
        if (removed) { request.cancelDeadline(); request.fail(failure); }
    }

    private final class Request<T> {
        private final Supplier<CompletionStage<T>> action;
        private final CompletableFuture<T> result = new CompletableFuture<T>() {
            // Observers cannot turn accepted physical work into a cancelled replay receipt.
            @Override public boolean cancel(boolean mayInterruptIfRunning) { return false; }
        };
        private ScheduledFuture<?> deadline;
        private final long admittedAt;
        private final long timeoutNanos;
        private volatile boolean waitingForScheduler;
        Request(Supplier<CompletionStage<T>> action, long timeoutMillis) {
            this.action = action;
            this.admittedAt = clock.getAsLong();
            this.timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        }
        synchronized void setDeadline(ScheduledFuture<?> value) {
            deadline = value;
            synchronized (ServerThreadRewardDispatch.this) {
                if (!pending.contains(this)) value.cancel(false);
            }
        }
        synchronized void cancelDeadline() { if (deadline != null) deadline.cancel(false); }
        void fail(Throwable failure) { result.completeExceptionally(failure); }
        void run() {
            boolean claimed;
            boolean disabled;
            boolean expired;
            synchronized (ServerThreadRewardDispatch.this) {
                claimed = pending.remove(this);
                disabled = closed || !plugin.isEnabled();
                expired = waitingForScheduler && clock.getAsLong() - admittedAt >= timeoutNanos;
            }
            if (!claimed) return;
            cancelDeadline();
            if (disabled) { fail(new IllegalStateException("Plugin disabled before reward scheduler admission")); return; }
            // The shared timer may be busy; a delayed timeout callback must not permit late effects.
            if (expired) { fail(new TimeoutException("Reward scheduler admission timed out")); return; }
            try {
                CompletionStage<T> stage = action.get();
                if (stage == null) throw new IllegalStateException("Reward action returned null completion stage");
                stage.whenComplete((value, failure) -> {
                    if (failure == null) result.complete(value);
                    else result.completeExceptionally(failure);
                });
            } catch (Throwable failure) { fail(failure); }
        }
    }
}

package com.bencodez.advancedcore.api.user.usercache;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/** Bounded plugin-local ownership shared by cached and uncached identities. */
public final class UserStorageOwnership {
    private final Slot[] slots = new Slot[64];

    public UserStorageOwnership() {
        for (int i = 0; i < slots.length; i++) slots[i] = new Slot();
    }

    public Slot owner(UUID identity) {
        return slots[Objects.requireNonNull(identity, "identity").hashCode() & (slots.length - 1)];
    }

    private final Object admission = new Object();
    private final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<Boolean> finalFlush = ThreadLocal.withInitial(() -> false);
    private int accepted;
    private boolean retiring;
    private boolean closed;
    private boolean finalRetirement;
    private Thread retiringThread;

    /** Count the whole accepted synchronous operation, including time waiting for its UUID owner. */
    public Scope admit() {
        synchronized (admission) {
            requireAdmission();
            accepted++;
        }
        depth.set(depth.get() + 1);
        return new Scope(Thread.currentThread());
    }

    private void requireAdmission() {
        if (closed || (retiring && depth.get() == 0 && !finalFlush.get())) {
            throw new IllegalStateException("User storage is retiring or closed");
        }
    }

    /** Reserve before submission; queued work remains accepted until its actual body settles. */
    public void submit(java.util.concurrent.Executor executor, Runnable work) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(work, "work");
        if (finalFlush.get()) throw new IllegalStateException("Final storage flush cannot submit asynchronous work");
        synchronized (admission) { requireAdmission(); accepted++; }
        java.util.concurrent.atomic.AtomicBoolean claimed = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable acceptedWork = () -> {
            if (!claimed.compareAndSet(false, true)) throw new IllegalStateException("Accepted storage work was already claimed");
            depth.set(depth.get() + 1);
            try { work.run(); }
            finally { leave(); }
        };
        try { executor.execute(acceptedWork); }
        catch (RuntimeException | Error failure) {
            if (claimed.compareAndSet(false, true)) release();
            throw failure;
        }
    }

    private void leave() {
        int previous = depth.get();
        if (previous <= 0) throw new IllegalStateException("Storage admission is not owned");
        if (previous == 1) depth.remove(); else depth.set(previous - 1);
        release();
    }

    private void release() {
        synchronized (admission) {
            if (accepted <= 0) throw new IllegalStateException("Storage admission was already released");
            accepted--;
            admission.notifyAll();
        }
    }

    public final class Scope implements AutoCloseable {
        private final Thread thread;
        private boolean released;
        private Scope(Thread thread) { this.thread = thread; }
        @Override public void close() {
            if (Thread.currentThread() != thread) throw new IllegalStateException("Storage admission belongs to another thread");
            if (!released) { released = true; leave(); }
        }
    }

    /** Seal admission and close only after accepted work and the synchronous final flush acknowledge. */
    public void retire(long timeout, java.util.concurrent.TimeUnit unit, Runnable flush, Runnable close) {
        transition(timeout, unit, flush, close, true);
    }

    /** Replace a provider only after accepted work and old-provider flushing settle.
     * A failed transition stays sealed; only an explicit successful replacement
     * reopens admission. Final retirement can never be undone by replacement.
     */
    public void replace(long timeout, java.util.concurrent.TimeUnit unit, Runnable flush, Runnable publish) {
        transition(timeout, unit, flush, publish, false);
    }

    private void transition(long timeout, java.util.concurrent.TimeUnit unit, Runnable flush, Runnable close, boolean permanent) {
        Objects.requireNonNull(unit, "unit"); Objects.requireNonNull(flush, "flush"); Objects.requireNonNull(close, "close");
        if (timeout < 0) throw new IllegalArgumentException("timeout");
        if (depth.get() != 0 || finalFlush.get()) throw new IllegalStateException("Cannot retire from admitted storage work");
        long remaining = unit.toNanos(timeout);
        long started = System.nanoTime();
        synchronized (admission) {
            if (!permanent && finalRetirement) throw new IllegalStateException("Final storage retirement cannot be reopened");
            if (closed) return;
            if (retiringThread != null) throw new IllegalStateException("Storage retirement is already in progress");
            if (permanent) finalRetirement = true;
            retiring = true;
            retiringThread = Thread.currentThread();
        }
        try {
            synchronized (admission) {
                while (accepted != 0) {
                    long elapsed = System.nanoTime() - started;
                    if (elapsed >= remaining) throw new IllegalStateException("Accepted storage work has not settled; provider remains open");
                    try { java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(admission, remaining - elapsed); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Storage retirement was interrupted; provider remains open", interrupted);
                    }
                }
            }
            finalFlush.set(true);
            try { flush.run(); }
            finally { finalFlush.remove(); }
            synchronized (admission) {
                if (accepted != 0) throw new IllegalStateException("Final storage flush has not settled; provider remains open");
            }
            close.run();
            synchronized (admission) { closed = permanent; retiring = permanent; }
        } finally { synchronized (admission) { retiringThread = null; } }
    }

    boolean isFinalFlush() { return finalFlush.get(); }

    void requireFinalFlush() {
        if (!finalFlush.get()) throw new IllegalStateException("Final cache flush requires storage retirement ownership");
    }

    public static final class Slot {
        private final ReentrantLock lock = new ReentrantLock(true);
        private final AtomicLong revision = new AtomicLong();
        private boolean writing;

        private Slot() {}
        public ReentrantLock getLock() { return lock; }
        public long getRevision() { return revision.get(); }
        public boolean isWriting() {
            if (!lock.isHeldByCurrentThread()) throw new IllegalStateException("Storage read is not owned");
            return writing;
        }
        public void beginWrite() {
            if (!lock.isHeldByCurrentThread() || writing) throw new IllegalStateException("Recursive storage write");
            writing = true;
            // Even a failed attempt can have an uncertain committed outcome.
            revision.incrementAndGet();
        }
        public void endWrite() {
            if (!lock.isHeldByCurrentThread() || !writing) throw new IllegalStateException("Storage write is not owned");
            writing = false;
        }

    }
}

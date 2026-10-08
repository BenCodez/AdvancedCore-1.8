package com.bencodez.advancedcore.api.user.usercache;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class LegacyStorageAdmissionTest {
    @Test void finalFlushMayPerformSynchronousNestedStorageButCloseRejectsNewRootWork() {
        UserStorageOwnership owner = new UserStorageOwnership(); List<String> events = new ArrayList<>();
        owner.retire(0, TimeUnit.NANOSECONDS, () -> {
            try (UserStorageOwnership.Scope lease = owner.admit()) { events.add("flush"); }
        }, () -> { assertThrows(IllegalStateException.class, owner::admit); events.add("close"); });
        assertEquals(Arrays.asList("flush", "close"), events); assertThrows(IllegalStateException.class, owner::admit);
        owner.retire(0, TimeUnit.NANOSECONDS, () -> fail("Already closed"), () -> fail("Already closed"));
    }
    @Test void finalFlushCannotReserveAsyncWorkOrLeaveAnUnsettledScopeBeforeClose() {
        UserStorageOwnership owner = new UserStorageOwnership(); AtomicInteger closes = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> owner.retire(0, TimeUnit.NANOSECONDS,
            () -> owner.submit(Runnable::run, () -> fail("Async work forbidden")), closes::incrementAndGet));
        assertEquals(0, closes.get()); AtomicReference<UserStorageOwnership.Scope> leaked = new AtomicReference<>();
        assertThrows(IllegalStateException.class, () -> owner.retire(0, TimeUnit.NANOSECONDS, () -> leaked.set(owner.admit()), closes::incrementAndGet));
        assertEquals(0, closes.get()); leaked.get().close(); owner.retire(0, TimeUnit.NANOSECONDS, () -> {}, closes::incrementAndGet);
        assertEquals(1, closes.get());
    }
    @Test void queuedAcceptedWorkKeepsProviderOpenAndCanFinishNestedWritesAfterSealing() {
        UserStorageOwnership owner = new UserStorageOwnership(); AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicBoolean written = new AtomicBoolean(), closed = new AtomicBoolean();
        owner.submit(queued::set, () -> { try (UserStorageOwnership.Scope nested = owner.admit()) { written.set(true); } });
        assertThrows(IllegalStateException.class, () -> owner.retire(0, TimeUnit.NANOSECONDS, () -> fail("Work pending"), () -> closed.set(true)));
        assertFalse(written.get()); assertFalse(closed.get()); assertThrows(IllegalStateException.class, owner::admit);
        queued.get().run(); assertTrue(written.get()); assertThrows(IllegalStateException.class, queued.get()::run);
        owner.retire(0, TimeUnit.NANOSECONDS, () -> {}, () -> closed.set(true)); assertTrue(closed.get());
    }
    @Test void rejectedSubmissionReleasesReservationAndCannotFakeExecutedWork() {
        UserStorageOwnership owner = new UserStorageOwnership(); AtomicBoolean called = new AtomicBoolean();
        assertThrows(RejectedExecutionException.class, () -> owner.submit(work -> { throw new RejectedExecutionException(); }, () -> called.set(true)));
        owner.retire(0, TimeUnit.NANOSECONDS, () -> {}, () -> {}); assertFalse(called.get());
    }
    @Test void synchronousExecutorFailureReleasesExactlyOnceAndKeepsOriginalFailure() {
        UserStorageOwnership owner = new UserStorageOwnership(); IllegalStateException failure = new IllegalStateException("body failed");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> owner.submit(Runnable::run, () -> { throw failure; })));
        owner.retire(0, TimeUnit.NANOSECONDS, () -> {}, () -> {});
    }
    @Test void ownAdmittedWorkCannotRetireItselfAndScopeReleaseIsIdempotent() {
        UserStorageOwnership owner = new UserStorageOwnership(); UserStorageOwnership.Scope scope = owner.admit();
        assertThrows(IllegalStateException.class, () -> owner.retire(0, TimeUnit.NANOSECONDS, () -> {}, () -> {}));
        try (UserStorageOwnership.Scope nested = owner.admit()) {}
        scope.close(); scope.close(); owner.retire(0, TimeUnit.NANOSECONDS, () -> {}, () -> {});
    }
    @Test void failedFlushLeavesProviderOpenAndSealedUntilAnExplicitRetry() {
        UserStorageOwnership owner = new UserStorageOwnership(); IllegalStateException unavailable = new IllegalStateException("flush failed");
        AtomicInteger closes = new AtomicInteger();
        assertSame(unavailable, assertThrows(IllegalStateException.class, () -> owner.retire(0, TimeUnit.NANOSECONDS, () -> { throw unavailable; }, closes::incrementAndGet)));
        assertEquals(0, closes.get()); assertThrows(IllegalStateException.class, owner::admit);
        owner.retire(0, TimeUnit.NANOSECONDS, () -> {}, closes::incrementAndGet); assertEquals(1, closes.get());
    }
    @Test void interruptionPreservesInterruptAndNeverClosesPendingWork() {
        UserStorageOwnership owner = new UserStorageOwnership(); AtomicReference<Runnable> queued = new AtomicReference<>(); owner.submit(queued::set, () -> {});
        Thread.currentThread().interrupt();
        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> owner.retire(1, TimeUnit.SECONDS, () -> fail("pending"), () -> fail("pending")));
            assertTrue(failure.getCause() instanceof InterruptedException); assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        queued.get().run(); owner.retire(0, TimeUnit.NANOSECONDS, () -> {}, () -> {});
    }
    @Test void anotherThreadCannotReleaseAnOwnersScope() throws Exception {
        UserStorageOwnership owner = new UserStorageOwnership(); UserStorageOwnership.Scope scope = owner.admit(); ExecutorService worker = Executors.newSingleThreadExecutor();
        try { worker.submit(() -> assertThrows(IllegalStateException.class, scope::close)).get(5, TimeUnit.SECONDS); }
        finally { scope.close(); worker.shutdownNow(); }
        owner.retire(0, TimeUnit.NANOSECONDS, () -> {}, () -> {});
    }
    @Test void realAcceptedWorkerCanSettleWhileRetirementWaitsWithoutHoldingAdmissionMonitor() throws Exception {
        UserStorageOwnership owner = new UserStorageOwnership(); ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); AtomicBoolean closed = new AtomicBoolean();
        owner.submit(worker, () -> { entered.countDown(); try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException failure) { throw new AssertionError(failure); } });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        Thread closer = new Thread(() -> owner.retire(5, TimeUnit.SECONDS, () -> {}, () -> closed.set(true)));
        try { closer.start(); release.countDown(); closer.join(5000); assertFalse(closer.isAlive()); assertTrue(closed.get()); }
        finally { release.countDown(); worker.shutdownNow(); }
    }
}

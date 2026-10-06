package com.bencodez.advancedcore.api.user.usercache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.core.user.runtime.*;
import com.bencodez.advancedcore.core.user.storage.sql.SqlUserBackend;

@Timeout(15)
class SharedNativeNotificationLifecycleTest {
    private UserDataManager manager() {
        AdvancedCorePlugin plugin = mock(AdvancedCorePlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("SharedNativeNotificationLifecycleTest"));
        return new UserDataManager(plugin);
    }

    @Test void queuedNotificationFromRetiredGenerationIsDroppedAndCurrentGenerationRuns() throws Exception {
        UserDataManager manager = manager();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try {
            manager.getTimer().execute(() -> {
                entered.countDown();
                await(release);
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Runnable old = manager.captureSharedUserDataNotification(calls::incrementAndGet);
            manager.dispatchSharedUserDataNotification(old);
            manager.advanceSharedUserDataNotificationGeneration();
            manager.dispatchSharedUserDataNotification(calls::incrementAndGet);
            release.countDown();
            manager.getTimer().submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(1, calls.get());
            manager.closeSharedUserDataNotifications();
            manager.dispatchSharedUserDataNotification(calls::incrementAndGet);
            manager.getTimer().submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(1, calls.get());
        } finally {
            release.countDown();
            manager.getTimer().shutdownNow();
            assertTrue(manager.getTimer().awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void startedNotificationKeepsProducingRuntimeAliveUntilItsCallbackSettles() throws Exception {
        UserDataManager manager = manager();
        SqlUserBackend backend = mock(SqlUserBackend.class);
        when(backend.isOpen()).thenReturn(true);
        UserCacheOwner cache = mock(UserCacheOwner.class);
        SharedUserDataRuntime runtime = new SharedUserDataRuntime(backend, cache);
        manager.bindSharedUserDataNotificationLifecycle(backend,
                operation -> runtime.withStorageReadAdmission(() -> { operation.run(); return null; }));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService retirementWorker = Executors.newSingleThreadExecutor();
        try {
            manager.dispatchSharedUserDataNotification(() -> {
                entered.countDown();
                await(release);
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CompletableFuture<Void> receipt = runtime.closeAsync(retirementWorker).toCompletableFuture();
            assertTrue(runtime.isRetiring());
            assertThrows(TimeoutException.class, () -> receipt.get(100, TimeUnit.MILLISECONDS));
            verify(backend, never()).close();
            release.countDown();
            receipt.get(5, TimeUnit.SECONDS);
            verify(backend).close();
            assertTrue(runtime.isClosed());
        } finally {
            release.countDown();
            retirementWorker.shutdownNow();
            manager.getTimer().shutdownNow();
            assertTrue(retirementWorker.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(manager.getTimer().awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void asynchronousNotificationFailureIsRetainedWithoutRepeatingTheCommittedWork() throws Exception {
        UserDataManager manager = manager();
        IllegalStateException failure = new IllegalStateException("notification failed");
        AtomicInteger calls = new AtomicInteger();
        try {
            manager.dispatchSharedUserDataNotification(() -> {
                calls.incrementAndGet();
                throw failure;
            });
            manager.getTimer().submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(1, calls.get());
            assertSame(failure, manager.getLastDeferredStorageFailure());
        } finally {
            manager.getTimer().shutdownNow();
            assertTrue(manager.getTimer().awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void backendNotificationRebindingFencesCapturedPredecessorCallbacks() throws Exception {
        UserDataManager manager = manager();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try {
            manager.bindSharedUserDataNotificationLifecycle(mock(SqlUserBackend.class), Runnable::run);
            manager.getTimer().execute(() -> { entered.countDown(); await(release); });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Runnable predecessor = manager.captureSharedUserDataNotification(calls::incrementAndGet);
            manager.bindSharedUserDataNotificationLifecycle(mock(SqlUserBackend.class), Runnable::run);
            manager.dispatchSharedUserDataNotification(predecessor);
            manager.dispatchSharedUserDataNotification(calls::incrementAndGet);
            release.countDown();
            manager.getTimer().submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(1, calls.get());
        } finally {
            release.countDown();
            manager.getTimer().shutdownNow();
            assertTrue(manager.getTimer().awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
}

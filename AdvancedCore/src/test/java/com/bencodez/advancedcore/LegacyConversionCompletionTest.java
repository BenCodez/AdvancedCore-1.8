package com.bencodez.advancedcore;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import com.bencodez.advancedcore.api.user.*;
import com.bencodez.advancedcore.api.user.usercache.UserStorageOwnership;
import com.bencodez.advancedcore.api.rewards.ServerThreadRewardDispatch;

class LegacyConversionCompletionTest {
    @Test void voidConversionOnServerThreadCannotReadStorageInline() throws Exception {
        try(MockedStatic<Bukkit> bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);bukkit.when(Bukkit::getServer).thenReturn(mock(Server.class));
            AdvancedCorePlugin plugin=fixture();List<Runnable> queued=new ArrayList<>();scheduler(bukkit,plugin,queued);plugin.convertDataStorage(UserStorage.MYSQL,UserStorage.SQLITE);assertEquals(1,queued.size());
            verify(plugin.getUserManager(),never()).getAllKeysStrict(any());
        }
    }
    @Test void queuedCompletionCannotBeCancelledAndCloseFencesLateConversion() throws Exception {
        try(MockedStatic<Bukkit> bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);bukkit.when(Bukkit::getServer).thenReturn(mock(Server.class));
            AdvancedCorePlugin plugin=fixture();List<Runnable> queued=new ArrayList<>();scheduler(bukkit,plugin,queued);
            CompletionStage<Void> result=plugin.convertDataStorageAsync(UserStorage.MYSQL,UserStorage.SQLITE);
            assertFalse(result.toCompletableFuture().isDone());assertFalse(result.toCompletableFuture().cancel(false));assertEquals(1,queued.size());
            plugin.getRewardDispatch().close();assertThrows(CompletionException.class,() -> result.toCompletableFuture().join());
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);queued.get(0).run();verify(plugin.getUserManager(),never()).getAllKeysStrict(any());
        }
    }
    @Test void schedulerRejectionPreservesFailureWithoutStorageEffects() throws Exception {
        try(MockedStatic<Bukkit> bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);AdvancedCorePlugin plugin=fixture();ScheduledExecutorService timer=mock(ScheduledExecutorService.class);when(plugin.getTimer()).thenReturn(timer);
            RejectedExecutionException failure=new RejectedExecutionException("fixture rejected");when(timer.schedule(any(Runnable.class),anyLong(),any(TimeUnit.class))).thenThrow(failure);
            assertSame(failure,assertThrows(CompletionException.class,() -> plugin.convertDataStorageAsync(UserStorage.MYSQL,UserStorage.SQLITE).toCompletableFuture().join()).getCause());
            verify(plugin.getUserManager(),never()).getAllKeysStrict(any());
        }
    }
    @Test void admittedNativeCopyMustPhysicallyFinishEvenAfterDispatcherClose() throws Exception {
        AdvancedCorePlugin plugin=fixture();List<Runnable> queued=new ArrayList<>();CompletionStage<Void> result;
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(plugin.getUserManager().getAllKeysStrict(any())).thenAnswer(call -> {entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));return new HashMap<>();});
        try(MockedStatic<Bukkit> bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);scheduler(bukkit,plugin,queued);result=plugin.convertDataStorageAsync(UserStorage.MYSQL,UserStorage.SQLITE);
        }
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<?> physical=worker.submit(() -> {try(MockedStatic<Bukkit> bukkit=mockStatic(Bukkit.class)){bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);queued.get(0).run();}});
            assertTrue(entered.await(2,TimeUnit.SECONDS));plugin.getRewardDispatch().close();assertFalse(result.toCompletableFuture().isDone());assertFalse(result.toCompletableFuture().cancel(false));
            release.countDown();physical.get(2,TimeUnit.SECONDS);result.toCompletableFuture().get(2,TimeUnit.SECONDS);
            try(UserStorageOwnership.Scope normal=plugin.getUserStorageOwnership().admit()) {}
        }finally{release.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(2,TimeUnit.SECONDS));}
    }
    @Test void workerFailureSettlesOriginalExceptionAndNullTypesNeverSchedule() throws Exception {
        try(MockedStatic<Bukkit> bukkit=mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);AdvancedCorePlugin plugin=fixture();IllegalStateException failure=new IllegalStateException("source failed");when(plugin.getUserManager().getAllKeysStrict(any())).thenThrow(failure);
            assertSame(failure,assertThrows(CompletionException.class,() -> plugin.convertDataStorageAsync(UserStorage.MYSQL,UserStorage.SQLITE).toCompletableFuture().join()).getCause());
            assertThrows(CompletionException.class,() -> plugin.convertDataStorageAsync(null,UserStorage.SQLITE).toCompletableFuture().join());
            verify(plugin,never()).getTimer();
        }
    }
    void scheduler(MockedStatic<Bukkit> bukkit,AdvancedCorePlugin plugin,List<Runnable> queued) {
        ScheduledExecutorService timer=mock(ScheduledExecutorService.class);when(plugin.getTimer()).thenReturn(timer);when(timer.schedule(any(Runnable.class),anyLong(),any(TimeUnit.class))).thenReturn(mock(ScheduledFuture.class));
        org.bukkit.scheduler.BukkitScheduler scheduler=mock(org.bukkit.scheduler.BukkitScheduler.class);bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);when(scheduler.runTaskAsynchronously(eq(plugin),any(Runnable.class))).thenAnswer(call -> {queued.add(call.getArgument(1));return null;});
    }
    AdvancedCorePlugin fixture() throws Exception {
        AdvancedCorePlugin plugin=mock(AdvancedCorePlugin.class);when(plugin.isEnabled()).thenReturn(true);when(plugin.getUserStorageOwnership()).thenReturn(new UserStorageOwnership());
        UserManager users=mock(UserManager.class);when(plugin.getUserManager()).thenReturn(users);when(users.getAllKeysStrict(any())).thenReturn(new HashMap<>());
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());when(plugin.getRewardDispatch()).thenReturn(new ServerThreadRewardDispatch(plugin));
        doCallRealMethod().when(plugin).convertDataStorage(any(),any());doCallRealMethod().when(plugin).convertDataStorageAsync(any(),any());return plugin;
    }
}

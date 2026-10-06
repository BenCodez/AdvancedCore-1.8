package com.bencodez.advancedcore.api.inventory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.AdvancedCorePlugin;

class LegacyViewerTimerAdmissionTest {
    @Test void completedClickFuturesAreReclaimedDuringTheNextViewerRegistration() {
        AdvancedCorePlugin plugin = mock(AdvancedCorePlugin.class);
        ScheduledExecutorService timer = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> completed = mock(ScheduledFuture.class), pending = mock(ScheduledFuture.class);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(plugin.getInventoryTimer()).thenReturn(timer);
        doReturn(completed, pending).when(timer).schedule(any(Runnable.class), eq(100L), eq(TimeUnit.MILLISECONDS));
        BInventory inventory = new BInventory("Delayed tasks");
        inventory.addDelayedTask(player, plugin, 100, () -> {});
        when(completed.isDone()).thenReturn(true);
        inventory.addDelayedTask(player, plugin, 100, () -> {});
        inventory.cancelTimer(player);
        verify(completed, never()).cancel(anyBoolean());
        verify(pending).cancel(true);
    }

    @Test void cancellationCannotPassAnInProgressRegistrationAndLeaveItsFutureUnowned() throws Exception {
        AdvancedCorePlugin plugin = mock(AdvancedCorePlugin.class);
        ScheduledExecutorService timer = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> scheduled = mock(ScheduledFuture.class);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(plugin.getInventoryTimer()).thenReturn(timer);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), cancelling = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)); return scheduled; })
            .when(timer).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
        ExecutorService workers = Executors.newFixedThreadPool(2);
        BInventory inventory = new BInventory("Registration ownership");
        try {
            Future<?> registration = workers.submit(() -> inventory.addUpdatingButton(player, plugin, 100, 250, () -> {}));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Future<?> cancellation = workers.submit(() -> { cancelling.countDown(); inventory.cancelTimer(player); });
            assertTrue(cancelling.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> cancellation.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            registration.get(2, TimeUnit.SECONDS); cancellation.get(2, TimeUnit.SECONDS);
            verify(scheduled).cancel(true);
            inventory.cancelTimer();
            verify(scheduled, times(1)).cancel(true);
        } finally {
            release.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
}

package com.bencodez.advancedcore.tests.inventory;

import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.*;
import com.bencodez.advancedcore.api.inventory.BInventoryButton;
import static org.mockito.Mockito.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.inventory.BInventory;

class BInventoryTimerTest {
    @Test
    void reservedSlotsRemainVisibleToVoteUrlAllocation() {
        BInventory inventory = new BInventory("Vote URL slot allocation");
        BInventoryButton button = mock(BInventoryButton.class);
        assertFalse(inventory.isSlotTaken(3));
        inventory.addButton(3, button);
        assertTrue(inventory.isSlotTaken(3));
        assertFalse(inventory.isSlotTaken(4));
        inventory.getButtons().remove(3);
        assertFalse(inventory.isSlotTaken(3));
    }

    @Test
    void usesIndependentInitialDelayAndIntervalAndCancelsTask() {
        AdvancedCorePlugin plugin = mock(AdvancedCorePlugin.class);
        ScheduledExecutorService timer = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        Runnable runnable = mock(Runnable.class);
        when(plugin.getInventoryTimer()).thenReturn(timer);
        doReturn(future).when(timer).scheduleWithFixedDelay(eq(runnable), eq(0L), eq(250L), eq(TimeUnit.MILLISECONDS));
        BInventory inventory = new BInventory("Timer regression");
        inventory.addUpdatingButton(plugin, 0L, 250L, runnable);
        verify(timer).scheduleWithFixedDelay(runnable, 0L, 250L, TimeUnit.MILLISECONDS);
        inventory.cancelTimer();
        inventory.cancelTimer();
        verify(future, times(1)).cancel(true);
    }
}

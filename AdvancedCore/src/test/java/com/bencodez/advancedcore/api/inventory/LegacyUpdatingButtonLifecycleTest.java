package com.bencodez.advancedcore.api.inventory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.item.ItemBuilder;
import com.bencodez.simpleapi.player.PlayerUtils;
import com.bencodez.simpleapi.scheduler.BukkitScheduler;

class LegacyUpdatingButtonLifecycleTest {
    @Test void itemConstructionAndPublicationWaitForOwnerExecution() {
        try (Fixture f = new Fixture()) {
            f.button.update(f.player);
            verify(f.builder, never()).toItemStack(any(Player.class));
            assertEquals(1, f.owner.size());
            f.owner.remove(0).run();
            verify(f.builder).toItemStack(f.player);
            verify(f.top).setItem(2, f.item);
        }
    }
    @Test void periodicUpdatesAreBoundedToOneQueuedOwnerCallback() {
        try (Fixture f = new Fixture()) {
            f.button.load(f.player);
            for (int i = 0; i < 100; i++) f.periodic.run();
            assertEquals(1, f.owner.size());
            f.owner.remove(0).run();
            f.periodic.run();
            assertEquals(1, f.owner.size());
            verify(f.inventory).addUpdatingButton(eq(f.player), eq(f.plugin), eq(100L), eq(250L), any(Runnable.class));
        }
    }
    @Test void anOldPageCallbackCannotWriteOrCancelTheReplacement() {
        try (Fixture f = new Fixture()) {
            f.button.load(f.player);
            f.periodic.run();
            Inventory replacement = mock(Inventory.class);
            f.players.when(() -> PlayerUtils.getTopInventory(f.player)).thenReturn(replacement);
            f.owner.remove(0).run();
            verify(f.builder, never()).toItemStack(any(Player.class));
            verify(replacement, never()).setItem(anyInt(), any());
            verify(f.inventory, never()).cancelTimer(f.player);
        }
    }
    @Test void closedViewerCancelsOnlyThatViewerEvenWhenDataIsNotReady() {
        try (Fixture f = new Fixture()) {
            when(f.plugin.isLoadUserData()).thenReturn(true);
            when(f.inventory.isOpen(f.player)).thenReturn(false);
            f.button.load(f.player);
            f.periodic.run();
            f.owner.remove(0).run();
            verify(f.inventory).cancelTimer(f.player);
            verify(f.inventory, never()).cancelTimer();
            verify(f.builder, never()).toItemStack(any(Player.class));
        }
    }
    @Test void pagedUpdatesMapSourceSlotsAndDoNotOverwriteNavigation() {
        try (Fixture f = new Fixture()) {
            when(f.inventory.isPages()).thenReturn(true);
            when(f.inventory.getMaxInvSize()).thenReturn(54);
            when(f.top.getHolder()).thenReturn(new GUISession(f.inventory, 2));
            f.button.setSlot(45);
            f.button.update(f.player);
            f.owner.remove(0).run();
            verify(f.top).setItem(0, f.item);
            clearInvocations(f.top);
            f.button.setSlot(90);
            f.button.update(f.player);
            f.owner.remove(0).run();
            verify(f.top, never()).setItem(anyInt(), any());
        }
    }
    @Test void nullUpdateDoesNotCancelAnotherViewer() {
        try (Fixture f = new Fixture()) {
            f.button.result = null;
            f.button.load(f.player);
            f.periodic.run();
            f.owner.remove(0).run();
            verify(f.inventory).cancelTimer(f.player);
            verify(f.inventory, never()).cancelTimer();
        }
    }
    @Test void delayedClickTaskBelongsToTheViewerAndKeepsItsOriginalTarget() {
        try (Fixture f = new Fixture()) {
            BInventory.ClickEvent click = mock(BInventory.ClickEvent.class);
            when(click.getPlayer()).thenReturn(f.player);
            when(click.getInventory()).thenReturn(f.top);
            f.button.updateOnClick().delay(100);
            f.button.onClick(click, f.inventory);
            verify(f.inventory).addDelayedTask(eq(f.player), eq(f.plugin), eq(100L), any(Runnable.class));
            f.delayed.run();
            f.players.when(() -> PlayerUtils.getTopInventory(f.player)).thenReturn(mock(Inventory.class));
            f.owner.remove(0).run();
            verify(f.builder, never()).toItemStack(any(Player.class));
        }
    }
    @Test void failedSchedulerAdmissionReleasesCoalescingWithoutHidingFailure() {
        try (Fixture f = new Fixture()) {
            f.button.load(f.player);
            doThrow(new IllegalStateException("scheduler rejected")).when(f.scheduler)
                .runTask(eq(f.plugin), any(Runnable.class), eq(f.player));
            assertThrows(IllegalStateException.class, () -> f.periodic.run());
            doAnswer(call -> { f.owner.add(call.getArgument(1)); return null; }).when(f.scheduler)
                .runTask(eq(f.plugin), any(Runnable.class), eq(f.player));
            f.periodic.run();
            assertEquals(1, f.owner.size());
        }
    }
    @Test void configuredFillSlotsIgnoreNullsAndDoNotFallThroughToTheButtonSlot() {
        try (Fixture f = new Fixture()) {
            when(f.builder.getFillSlots()).thenReturn(Arrays.asList(0, null, 1));
            Button fill = new Button(f.plugin, f.builder);
            fill.setInv(f.inventory); fill.setSlot(2);
            fill.update(f.player);
            f.owner.remove(0).run();
            verify(f.top).setItem(0, f.item);
            verify(f.top).setItem(1, f.item);
            verify(f.top, never()).setItem(eq(2), any());
        }
    }

    private static final class Button extends UpdatingBInventoryButton {
        ItemBuilder result;
        Button(AdvancedCorePlugin plugin, ItemBuilder builder) { super(plugin, builder, 100, 250); result = builder; }
        public void onClick(BInventory.ClickEvent event) {}
        public ItemBuilder onUpdate(Player player) { return result; }
    }
    private static final class Fixture implements AutoCloseable {
        final AdvancedCorePlugin plugin = mock(AdvancedCorePlugin.class);
        final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        final BInventory inventory = mock(BInventory.class);
        final Player player = mock(Player.class);
        final Inventory top = mock(Inventory.class);
        final ItemBuilder builder = mock(ItemBuilder.class);
        final ItemStack item = mock(ItemStack.class);
        final List<Runnable> owner = new ArrayList<>();
        final MockedStatic<PlayerUtils> players = mockStatic(PlayerUtils.class);
        final Button button;
        Runnable periodic, delayed;
        Fixture() {
            when(plugin.isEnabled()).thenReturn(true);
            when(plugin.getBukkitScheduler()).thenReturn(scheduler);
            when(inventory.isOpen(player)).thenReturn(true);
            when(inventory.getRenderingInventory()).thenReturn(top);
            when(top.getSize()).thenReturn(54);
            when(builder.toItemStack(player)).thenReturn(item);
            players.when(() -> PlayerUtils.getTopInventory(player)).thenReturn(top);
            doAnswer(call -> { owner.add(call.getArgument(1)); return null; }).when(scheduler)
                .runTask(eq(plugin), any(Runnable.class), eq(player));
            doAnswer(call -> { periodic = call.getArgument(4); return null; }).when(inventory)
                .addUpdatingButton(eq(player), eq(plugin), anyLong(), anyLong(), any(Runnable.class));
            doAnswer(call -> { delayed = call.getArgument(3); return null; }).when(inventory)
                .addDelayedTask(eq(player), eq(plugin), anyLong(), any(Runnable.class));
            button = new Button(plugin, builder); button.setInv(inventory); button.setSlot(2);
        }
        public void close() { players.close(); }
    }
}

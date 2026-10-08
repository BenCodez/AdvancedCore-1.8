package com.bencodez.advancedcore.tests.inventory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import com.bencodez.advancedcore.api.inventory.BInventoryListener;
import com.bencodez.advancedcore.AdvancedCoreConfigOptions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.inventory.BInventory;
import com.bencodez.advancedcore.api.inventory.BInventoryButton;
import com.bencodez.advancedcore.api.inventory.GUISession;
import com.bencodez.advancedcore.api.messages.PlaceholderUtils;
import com.bencodez.simpleapi.scheduler.BukkitScheduler;
import com.bencodez.simpleapi.player.PlayerUtils;

class LegacyInventoryPaginationIntegrationTest {
    @Test
    void exactContentBoundaryIncludesTheLastButtonOnItsOwnPage() {
        try (Fixture f = new Fixture()) {
            BInventoryButton button = f.button(90);
            f.gui.setPages(true);
            f.gui.openInventory(f.player);
            assertEquals(3, f.gui.getMaxPage());
            assertEquals("3", f.gui.getPlaceholders().get("totalpages"));
            f.gui.openInventory(f.player, 3);
            assertEquals(3, f.session().getPage());
            verify(f.current()).setItem(0, f.item);
            verify(button).load(f.player);
            verify(button).setSlot(90);
        }
    }

    @Test
    void requestedPageClampsToLastPageAndUsesItsSourceOffset() {
        try (Fixture f = new Fixture()) {
            f.button(45);
            f.gui.setPages(true);
            f.gui.openInventory(f.player, 99);
            assertEquals(2, f.gui.getMaxPage());
            assertEquals(2, f.gui.getPage());
            assertEquals(2, f.session().getPage());
            assertEquals("2", f.gui.getPlaceholders().get("currentpage"));
            verify(f.current()).setItem(0, f.item);
            verify(f.current()).setItem(45, f.previous);
            verify(f.current()).setItem(53, f.next);
        }
    }

    @Test
    void invalidPageFailsBeforeCreatingOrPublishingAnInventory() {
        try (Fixture f = new Fixture()) {
            assertThrows(IllegalArgumentException.class, () -> f.gui.openInventory(f.player, 0));
            assertThrows(IllegalArgumentException.class, () -> f.gui.openInventory(f.player, -1));
            assertTrue(f.created.isEmpty());
            verify(f.player, never()).openInventory(any(Inventory.class));
        }
    }

    @Test
    void smallestLegacyInventoryDoesNotDivideByZero() {
        try (Fixture f = new Fixture()) {
            f.gui.setMaxInvSize(0); // existing size normalization produces nine slots
            f.gui.setPages(true);
            f.button(1);
            assertDoesNotThrow(() -> f.gui.openInventory(f.player));
            assertEquals(2, f.gui.getMaxPage());
            f.gui.openInventory(f.player, 2);
            assertEquals(2, f.session().getPage());
            verify(f.current()).setItem(0, f.item);
            verify(f.current()).setItem(1, f.previous);
            verify(f.current()).setItem(8, f.next);
        }
    }

    @Test
    void disablingPaginationResetsTheExposedPageCount() {
        try (Fixture f = new Fixture()) {
            f.button(90);
            f.gui.setPages(true);
            f.gui.openInventory(f.player, 3);
            f.gui.setPages(false);
            assertEquals(1, f.gui.getMaxPage());
        }
    }

    @Test
    void listenerMapsSecondPageContentAndRejectsTheNavigationRowAsContent() {
        for (int inventorySize : new int[] {54, 9}) {
        int contentSize = Math.max(1, inventorySize - 9);
        try (Fixture f = new Fixture(); MockedStatic<GUISession> sessions = mockStatic(GUISession.class)) {
            BInventory gui = mock(BInventory.class);
            GUISession session = new GUISession(gui, 2);
            sessions.when(() -> GUISession.extractSession(f.player)).thenReturn(session);
            when(gui.isPages()).thenReturn(true);
            when(gui.isClickAsync()).thenReturn(true);
            when(gui.getMaxInvSize()).thenReturn(inventorySize);
            when(gui.getMaxPage()).thenReturn(2);
            BInventoryButton target = mock(BInventoryButton.class);
            java.util.Map<Integer, BInventoryButton> buttons = new java.util.HashMap<>();
            buttons.put(contentSize, target);
            when(gui.getButtons()).thenReturn(buttons);
            when(gui.getPageButtons()).thenReturn(new ArrayList<>());
            AdvancedCoreConfigOptions options = mock(AdvancedCoreConfigOptions.class);
            when(f.plugin.getOptions()).thenReturn(options);
            doAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; })
                    .when(f.scheduler).runTaskAsynchronously(eq(f.plugin), any(Runnable.class));
            InventoryClickEvent event = mock(InventoryClickEvent.class);
            org.bukkit.inventory.PlayerInventory carried = mock(org.bukkit.inventory.PlayerInventory.class);
            when(f.player.getInventory()).thenReturn(carried);
            when(carried.firstEmpty()).thenReturn(-1);
            Inventory top = mock(Inventory.class);
            when(top.getType()).thenReturn(InventoryType.CHEST);
            when(event.getWhoClicked()).thenReturn(f.player);
            when(event.getClickedInventory()).thenReturn(top);
            f.players.when(() -> PlayerUtils.getTopInventory(f.player)).thenReturn(top);
            when(event.getSlot()).thenReturn(0);
            BInventoryListener listener = new BInventoryListener(f.plugin);
            listener.onInventoryClick(event);
            verify(gui).onClick(event, target);
            verify(event).setCancelled(true);
            clearInvocations(gui);
            when(event.getSlot()).thenReturn(contentSize);
            listener.onInventoryClick(event);
            verify(gui, never()).onClick(any(), any());
            verify(gui).openInventory(f.player, 1);
        }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Player player = mock(Player.class);
        final AdvancedCorePlugin plugin = mock(AdvancedCorePlugin.class);
        final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        final ItemStack item = mock(ItemStack.class);
        final ItemStack previous = mock(ItemStack.class);
        final ItemStack next = mock(ItemStack.class);
        final List<Inventory> created = new ArrayList<>();
        final List<InventoryHolder> holders = new ArrayList<>();
        final MockedStatic<AdvancedCorePlugin> plugins = mockStatic(AdvancedCorePlugin.class);
        final MockedStatic<PlaceholderUtils> placeholders = mockStatic(PlaceholderUtils.class);
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final MockedStatic<PlayerUtils> players = mockStatic(PlayerUtils.class);
        final BInventory gui;
        Fixture() {
            plugins.when(AdvancedCorePlugin::getInstance).thenReturn(plugin);
            when(plugin.getBukkitScheduler()).thenReturn(scheduler);
            placeholders.when(() -> PlaceholderUtils.replacePlaceHolder(anyString(), any(HashMap.class)))
                    .thenAnswer(call -> call.getArgument(0));
            placeholders.when(() -> PlaceholderUtils.replaceJavascript(eq(player), anyString()))
                    .thenAnswer(call -> call.getArgument(1));
            bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), anyInt(), anyString()))
                    .thenAnswer(call -> {
                        Inventory inventory = mock(Inventory.class);
                        InventoryHolder holder = call.getArgument(0);
                        when(inventory.getHolder()).thenReturn(holder);
                        created.add(inventory);
                        holders.add(holder);
                        return inventory;
                    });
            gui = new BInventory("Pagination regression");
            gui.setPrevItem(previous);
            gui.setNextItem(next);
        }
        BInventoryButton button(int slot) {
            BInventoryButton button = mock(BInventoryButton.class);
            when(button.getItem(eq(player), any(HashMap.class))).thenReturn(item);
            gui.addButton(slot, button);
            return button;
        }
        Inventory current() { return created.get(created.size() - 1); }
        GUISession session() { return (GUISession) holders.get(holders.size() - 1); }
        public void close() { players.close(); bukkit.close(); placeholders.close(); plugins.close(); }
    }
}

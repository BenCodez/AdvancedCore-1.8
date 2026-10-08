package com.bencodez.advancedcore.tests.inventory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.event.Event.Result;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import com.bencodez.advancedcore.AdvancedCoreConfigOptions;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.inventory.*;
import com.bencodez.advancedcore.api.item.FullInventoryHandler;
import com.bencodez.simpleapi.player.PlayerUtils;
import com.bencodez.simpleapi.scheduler.BukkitScheduler;

class LegacyInventoryListenerOwnershipTest {
    @Test
    void fullInventoryCheckIsQueuedToOwnerAndNeverReadsInventoryInAsyncQueue() {
        try (Fixture f = new Fixture()) {
            f.sessions.when(() -> GUISession.extractSession(f.player)).thenReturn(null);
            when(f.carried.firstEmpty()).thenReturn(0);
            f.listener.onInventoryClick(f.event);
            verify(f.carried, never()).firstEmpty();
            assertTrue(f.async.isEmpty());
            assertEquals(1, f.owner.size());
            f.owner.remove(0).run();
            verify(f.handler).check(f.player);
        }
    }

    @Test
    void pagedNavigationRunsDuringOwnerAdmissionRatherThanInAnAsyncCallback() {
        try (Fixture f = new Fixture()) {
            when(f.gui.isPages()).thenReturn(true);
            when(f.event.getSlot()).thenReturn(45);
            f.listener.onInventoryClick(f.event);
            verify(f.gui).openInventory(f.player, 1);
            verify(f.gui).playSound(f.player);
            assertTrue(f.async.isEmpty());
            verify(f.event).setCancelled(true);
            verify(f.event).setResult(Result.DENY);
        }
    }

    @Test
    void defaultAsyncCallbackStillDefersButCloseAndCursorAdmissionStayOnOwner() {
        try (Fixture f = new Fixture()) {
            when(f.gui.isClickAsync()).thenReturn(true);
            f.listener.onInventoryClick(f.event);
            verify(f.gui).closeInv(f.player, f.button);
            verify(f.player).setItemOnCursor(any());
            verify(f.gui, never()).onClick(any(), any());
            assertEquals(1, f.async.size());
            f.async.remove(0).run();
            verify(f.gui).onClick(f.event, f.button);
        }
    }

    @Test
    void synchronousOptInRunsCallbackOnEventAdmission() {
        try (Fixture f = new Fixture()) {
            when(f.gui.isClickAsync()).thenReturn(false);
            f.listener.onInventoryClick(f.event);
            verify(f.gui).onClick(f.event, f.button);
            assertTrue(f.async.isEmpty());
        }
    }

    @Test
    void bottomChestIsNotMistakenForTheManagedTopInventory() {
        try (Fixture f = new Fixture()) {
            Inventory bottom = mock(Inventory.class);
            when(bottom.getType()).thenReturn(InventoryType.CHEST);
            when(f.event.getClickedInventory()).thenReturn(bottom);
            f.listener.onInventoryClick(f.event);
            verify(f.event).setCancelled(true);
            verify(f.gui, never()).onClick(any(), any());
            verify(f.player, never()).setItemOnCursor(any());
            assertTrue(f.async.isEmpty());
        }
    }

    @Test
    void outsideClickRemainsCancelledWithoutDispatchOrCursorChanges() {
        try (Fixture f = new Fixture()) {
            when(f.event.getClickedInventory()).thenReturn(null);
            f.listener.onInventoryClick(f.event);
            verify(f.event).setCancelled(true);
            verify(f.event).setResult(Result.DENY);
            verify(f.player, never()).setItemOnCursor(any());
            assertTrue(f.async.isEmpty());
        }
    }

    @Test
    void pagedContentUsesTheSessionPageBeforeAsyncDispatch() {
        try (Fixture f = new Fixture()) {
            when(f.gui.isPages()).thenReturn(true);
            when(f.gui.isClickAsync()).thenReturn(true);
            f.buttons.clear(); f.buttons.put(45, f.button);
            f.listener.onInventoryClick(f.event);
            verify(f.gui).closeInv(f.player, f.button);
            assertEquals(1, f.async.size());
            f.async.remove(0).run();
            verify(f.gui).onClick(f.event, f.button);
        }
    }

    @Test
    void spamAdmissionRejectsBeforeQueuingCallback() {
        try (Fixture f = new Fixture()) {
            when(f.options.getSpamClickTime()).thenReturn(10000);
            when(f.gui.getLastPressTime()).thenReturn(System.currentTimeMillis());
            when(f.options.getSpamClickMessage()).thenReturn("Slow down");
            f.listener.onInventoryClick(f.event);
            verify(f.gui).forceClose(f.player);
            verify(f.player).sendMessage("Slow down");
            assertTrue(f.async.isEmpty());
        }
    }

    @Test
    void noConfiguredButtonDoesNotQueueCallback() {
        try (Fixture f = new Fixture()) {
            f.buttons.clear();
            f.listener.onInventoryClick(f.event);
            verify(f.gui, never()).onClick(any(), any());
            assertTrue(f.async.isEmpty());
        }
    }

    @Test
    void equalNativeInventoryWrappersAreAcceptedWithoutJavaObjectIdentity() {
        try (Fixture f = new Fixture()) {
            Inventory wrapper = (Inventory) java.lang.reflect.Proxy.newProxyInstance(
                    Inventory.class.getClassLoader(), new Class<?>[] {Inventory.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("equals")) return args[0] == f.top;
                        if (method.getName().equals("hashCode")) return f.top.hashCode();
                        return method.invoke(f.top, args);
                    });
            when(f.event.getClickedInventory()).thenReturn(wrapper);
            f.listener.onInventoryClick(f.event);
            verify(f.gui).onClick(f.event, f.button);
        }
    }

    @Test
    void asyncCallbackSoundDefersNativeLocationAndSoundAccessToOwner() {
        try (Fixture f = new Fixture(); MockedStatic<org.bukkit.Bukkit> bukkit = mockStatic(org.bukkit.Bukkit.class);
                MockedStatic<AdvancedCorePlugin> plugins = mockStatic(AdvancedCorePlugin.class)) {
            plugins.when(AdvancedCorePlugin::getInstance).thenReturn(f.plugin);
            bukkit.when(org.bukkit.Bukkit::isPrimaryThread).thenReturn(false);
            when(f.options.getClickSoundSound()).thenReturn(org.bukkit.Sound.CLICK);
            when(f.options.getClickSoundVolume()).thenReturn(1.0);
            when(f.options.getClickSoundPitch()).thenReturn(1.0);
            BInventory gui = new BInventory("Owner sound");
            gui.playSound(f.player);
            verify(f.player, never()).getLocation();
            assertEquals(1, f.owner.size());
            bukkit.when(org.bukkit.Bukkit::isPrimaryThread).thenReturn(true);
            f.owner.remove(0).run();
            verify(f.player).getLocation();
            verify(f.player).playSound(any(), eq(org.bukkit.Sound.CLICK), eq(1.0F), eq(1.0F));
        }
    }

    @Test
    void disabledSoundDoesNotQueueAJobOrTouchBukkit() {
        try (Fixture f = new Fixture()) {
            new BInventory("Silent").noSound().playSound(f.player);
            assertTrue(f.owner.isEmpty());
            assertTrue(f.async.isEmpty());
            verify(f.player, never()).getLocation();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Player player = mock(Player.class);
        final PlayerInventory carried = mock(PlayerInventory.class);
        final Inventory top = mock(Inventory.class);
        final InventoryClickEvent event = mock(InventoryClickEvent.class);
        final AdvancedCorePlugin plugin = mock(AdvancedCorePlugin.class);
        final AdvancedCoreConfigOptions options = mock(AdvancedCoreConfigOptions.class);
        final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        final FullInventoryHandler handler = mock(FullInventoryHandler.class);
        final BInventory gui = mock(BInventory.class);
        final BInventoryButton button = mock(BInventoryButton.class);
        final HashMap<Integer, BInventoryButton> buttons = new HashMap<>();
        final List<Runnable> owner = new ArrayList<>(), async = new ArrayList<>();
        final MockedStatic<GUISession> sessions = mockStatic(GUISession.class);
        final MockedStatic<PlayerUtils> players = mockStatic(PlayerUtils.class);
        final BInventoryListener listener = new BInventoryListener(plugin);
        Fixture() {
            when(plugin.getBukkitScheduler()).thenReturn(scheduler);
            when(plugin.getOptions()).thenReturn(options);
            when(plugin.getFullInventoryHandler()).thenReturn(handler);
            when(options.getSpamClickMessage()).thenReturn("");
            when(player.getInventory()).thenReturn(carried);
            when(carried.firstEmpty()).thenReturn(-1);
            when(top.getType()).thenReturn(InventoryType.CHEST);
            when(top.getSize()).thenReturn(54);
            when(event.getWhoClicked()).thenReturn(player);
            when(event.getInventory()).thenReturn(top);
            when(event.getClickedInventory()).thenReturn(top);
            players.when(() -> PlayerUtils.getTopInventory(player)).thenReturn(top);
            sessions.when(() -> GUISession.extractSession(player)).thenReturn(new GUISession(gui, 2));
            buttons.put(0, button);
            when(gui.getButtons()).thenReturn(buttons);
            when(gui.getMaxInvSize()).thenReturn(54);
            when(gui.getMaxPage()).thenReturn(2);
            when(gui.getPageButtons()).thenReturn(new ArrayList<>());
            doAnswer(call -> { owner.add(call.getArgument(1)); return null; })
                    .when(scheduler).runTask(eq(plugin), any(Runnable.class), eq(player));
            doAnswer(call -> { async.add(call.getArgument(1)); return null; })
                    .when(scheduler).runTaskAsynchronously(eq(plugin), any(Runnable.class));
        }
        public void close() { players.close(); sessions.close(); }
    }
}

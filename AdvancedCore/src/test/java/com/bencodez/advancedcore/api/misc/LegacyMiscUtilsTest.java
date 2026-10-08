package com.bencodez.advancedcore.api.misc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Field;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.messages.PlaceholderUtils;
import com.bencodez.simpleapi.scheduler.BukkitScheduler;

class LegacyMiscUtilsTest {
    @Test void dateArithmeticUsesTheSuppliedDateAndDoesNotMutateIt() {
        MiscUtils misc = mock(MiscUtils.class, CALLS_REAL_METHODS);
        Date input = new Date(123456000L);
        assertEquals(123461000L, misc.addSeconds(input, 5).getTime());
        assertEquals(123451000L, misc.addSeconds(input, -5).getTime());
        assertEquals(123456000L, misc.addSeconds(input, 0).getTime());
        assertEquals(123456000L, input.getTime());
    }
    @Test void everyListOverloadStaggersCommandsAndStripsLeadingSlashes() throws Exception {
        try (Fixture f = new Fixture()) {
            ArrayList<String> commands = new ArrayList<>(Arrays.asList("/say first", "say second", "/say third"));
            f.misc.executeConsoleCommands(commands, new HashMap<>(), true);
            f.misc.executeConsoleCommands(f.player, commands, new HashMap<>(), true);
            f.misc.executeConsoleCommands("LegacyPlayer", commands, new HashMap<>(), true);
            assertEquals(Arrays.asList(0L,1L,2L,0L,1L,2L,0L,1L,2L), f.delays);
            assertEquals(Arrays.asList("say first","say second","say third",
                "say first","say second","say third","say first","say second","say third"), f.commands);
        }
    }
    @Test void bothSingleCommandOverloadsStripSlashesAfterPlaceholderReplacement() throws Exception {
        try (Fixture f = new Fixture()) {
            f.placeholders.when(() -> PlaceholderUtils.replacePlaceHolder(eq("%command%"), any()))
                .thenReturn("/say replaced");
            f.misc.executeConsoleCommands(f.player, "%command%", new HashMap<>());
            f.misc.executeConsoleCommands("LegacyPlayer", "%command%", new HashMap<>());
            assertEquals(Arrays.asList("say replaced", "say replaced"), f.commands);
        }
    }
    @Test void nonStaggeredAndEmptyCommandsRetainTheirBehavior() throws Exception {
        try (Fixture f = new Fixture()) {
            f.misc.executeConsoleCommands(new ArrayList<>(Arrays.asList("say one", "say two")), new HashMap<>(), false);
            f.misc.executeConsoleCommands(new ArrayList<>(), new HashMap<>(), true);
            f.misc.executeConsoleCommands((ArrayList<String>) null, new HashMap<>(), true);
            f.misc.executeConsoleCommands("LegacyPlayer", "", new HashMap<>());
            assertEquals(Arrays.asList(0L,0L), f.delays);
            assertEquals(Arrays.asList("say one","say two"), f.commands);
        }
    }
    private static class Fixture implements AutoCloseable {
        final MiscUtils misc = mock(MiscUtils.class, CALLS_REAL_METHODS);
        final AdvancedCorePlugin plugin = mock(AdvancedCorePlugin.class);
        final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        final Player player = mock(Player.class);
        final Server server = mock(Server.class);
        final ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        final ArrayList<Long> delays = new ArrayList<>();
        final ArrayList<String> commands = new ArrayList<>();
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final MockedStatic<PlaceholderUtils> placeholders = mockStatic(PlaceholderUtils.class, call -> {
            // These tests exercise command scheduling/dispatch, keeping existing placeholder results.
            if (call.getMethod().getName().equals("replaceJavascript")) return call.getArgument(1);
            if (call.getMethod().getName().equals("replacePlaceHolder")) return call.getArgument(0);
            return null;
        });
        Fixture() throws Exception {
            Field field = MiscUtils.class.getDeclaredField("plugin");field.setAccessible(true);field.set(misc,plugin);
            when(plugin.getBukkitScheduler()).thenReturn(scheduler);
            when(player.getName()).thenReturn("LegacyPlayer");
            bukkit.when(Bukkit::getServer).thenReturn(server);
            bukkit.when(Bukkit::getConsoleSender).thenReturn(console);
            doAnswer(call -> {commands.add(call.getArgument(1));return true;})
                .when(server).dispatchCommand(eq(console), anyString());
            doAnswer(call -> {delays.add(0L);((Runnable)call.getArgument(1)).run();return null;})
                .when(scheduler).runTask(eq(plugin), any(Runnable.class));
            doAnswer(call -> {delays.add(((Number)call.getArgument(2)).longValue());((Runnable)call.getArgument(1)).run();return null;})
                .when(scheduler).runTaskLater(eq(plugin), any(Runnable.class), anyLong());
            doAnswer(call -> {((Runnable)call.getArgument(1)).run();return null;})
                .when(scheduler).executeOrScheduleSync(eq(plugin), any(Runnable.class));
            doAnswer(call -> {((Runnable)call.getArgument(1)).run();return null;})
                .when(scheduler).executeOrScheduleSync(eq(plugin), any(Runnable.class), eq(player));
        }
        public void close() {placeholders.close();bukkit.close();}
    }
}

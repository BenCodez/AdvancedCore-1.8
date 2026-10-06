package com.bencodez.advancedcore.command;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import com.bencodez.advancedcore.AdvancedCoreConfigOptions;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.command.CommandHandler;
import com.bencodez.advancedcore.api.command.PlayerCommandHandler;
import com.bencodez.advancedcore.api.user.UserManager;
import com.bencodez.simpleapi.command.TabCompleteHandler;
import com.bencodez.simpleapi.scheduler.BukkitScheduler;

class CommandLoaderBulkPermissionTest {
    @Test void pairedBaseAndBulkPermissionReachOnlyBulkStorage() { checkBulk(false); }
    @Test void legacySetAllDataAliasRemainsBulkOnly() { checkBulk(true); }

    private void checkBulk(boolean legacy) {
        AdvancedCorePlugin plugin = mock(AdvancedCorePlugin.class);
        AdvancedCoreConfigOptions options = mock(AdvancedCoreConfigOptions.class);
        UserManager users = mock(UserManager.class);
        when(plugin.getOptions()).thenReturn(options);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getBukkitScheduler()).thenReturn(scheduler);
        doAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; })
                .when(scheduler).runTaskAsynchronously(eq(plugin), any(Runnable.class));
        when(plugin.getJenkinsSite()).thenReturn("");
        when(options.isMultiplePermissionChecks()).thenReturn(true);
        when(plugin.getUserManager()).thenReturn(users);
        when(users.getAllUUIDs()).thenReturn(new ArrayList<String>());
        Player sender = mock(Player.class);
        if (legacy) when(sender.hasPermission("Example.SetAllData")).thenReturn(true);
        else {
            when(sender.hasPermission("Example.SetData")).thenReturn(true);
            when(sender.hasPermission("Example.SetData.All")).thenReturn(true);
        }
        List<CommandHandler> commands = new CommandLoader(plugin).getBasicAdminCommands("Example");
        assertFalse(commands.stream().anyMatch(c -> Arrays.equals(c.getArgs(),
                new String[] {"User", "All", "SetData", "(text)", "(text)"})));
        PlayerCommandHandler command = (PlayerCommandHandler) commands.stream()
                .filter(c -> Arrays.equals(c.getArgs(), new String[] {"User", "(player)", "SetData", "(text)", "(text)"}))
                .findFirst().orElseThrow(() -> new AssertionError("Missing SetData"));
        TabCompleteHandler.getInstance().addTabCompleteOption("(player)");
        TabCompleteHandler.getInstance().addTabCompleteOption("(text)");
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(anyString())).thenReturn(null);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(Collections.emptyList());
            assertTrue(command.runCommand(sender, new String[] {"User", "all", "SetData", "rank", "trusted"}));
        }
        verify(users).getAllUUIDs();
        verify(users, never()).getUser("all");
        assertEquals(Arrays.asList("Example.SetData.All", "Example.SetAllData"), command.getAdditionalPermissions());
        if (legacy) assertFalse(command.hasPerm(sender));
    }
}

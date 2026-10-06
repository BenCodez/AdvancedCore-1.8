package com.bencodez.advancedcore.tests.item;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.item.FullInventoryHandler;
import com.bencodez.advancedcore.data.ServerData;
class LegacyOverflowMergeTest {
    @Test void appendingAnotherListRetainsTheSamePlayerAndBothItems() {
        FullInventoryHandler handler = handler(); UUID id = UUID.randomUUID();
        ItemStack first = mock(ItemStack.class), second = mock(ItemStack.class);
        handler.add(id, new ArrayList<>(Arrays.asList(first)));
        assertDoesNotThrow(() -> handler.add(id, new ArrayList<>(Arrays.asList(second))));
        assertEquals(Arrays.asList(first, second), handler.getItems().get(id));
        assertEquals(1, handler.getItems().size());
    }
    @Test void callerListChangesCannotReplaceAcceptedPendingItems() {
        FullInventoryHandler handler = handler(); UUID id = UUID.randomUUID(); ItemStack item = mock(ItemStack.class);
        ArrayList<ItemStack> supplied = new ArrayList<>(Arrays.asList(item));
        handler.add(id, supplied); supplied.clear(); assertEquals(Arrays.asList(item), handler.getItems().get(id));
    }
    private FullInventoryHandler handler() {
        AdvancedCorePlugin plugin = mock(AdvancedCorePlugin.class); ServerData data = mock(ServerData.class);
        when(plugin.getServerDataFile()).thenReturn(data); when(data.getData()).thenReturn(new YamlConfiguration());
        return new FullInventoryHandler(plugin) { @Override public void loadTimer() {} };
    }
}

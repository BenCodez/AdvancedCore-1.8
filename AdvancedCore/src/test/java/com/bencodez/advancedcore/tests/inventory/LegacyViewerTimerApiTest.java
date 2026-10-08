package com.bencodez.advancedcore.tests.inventory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import org.junit.jupiter.api.Test;
import org.bukkit.entity.Player;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.inventory.BInventory;

class LegacyViewerTimerApiTest {
    @Test void currentViewerScopedTimerApiIsAvailable() {
        assertDoesNotThrow(() -> BInventory.class.getMethod("addUpdatingButton", Player.class,
                AdvancedCorePlugin.class, long.class, long.class, Runnable.class));
        assertDoesNotThrow(() -> BInventory.class.getMethod("cancelTimer", Player.class));
    }
}

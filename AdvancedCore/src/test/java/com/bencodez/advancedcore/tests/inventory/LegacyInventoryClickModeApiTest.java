package com.bencodez.advancedcore.tests.inventory;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.api.inventory.BInventory;

class LegacyInventoryClickModeApiTest {
    @Test
    void currentMainClickModeApiIsAdditiveAndKeepsAsyncDefault() throws Exception {
        BInventory gui = new BInventory("Click modes");
        assertTrue((Boolean) BInventory.class.getMethod("isClickAsync").invoke(gui));
        assertSame(gui, BInventory.class.getMethod("runClicksSync").invoke(gui));
        assertFalse((Boolean) BInventory.class.getMethod("isClickAsync").invoke(gui));
        assertSame(gui, BInventory.class.getMethod("runClicksAsync").invoke(gui));
        assertTrue((Boolean) BInventory.class.getMethod("isClickAsync").invoke(gui));
        assertSame(gui, BInventory.class.getMethod("setClickAsync", boolean.class).invoke(gui, false));
        assertFalse((Boolean) BInventory.class.getMethod("isClickAsync").invoke(gui));
    }
}

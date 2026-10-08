package com.bencodez.advancedcore.api.misc;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.bukkit.entity.Player;

class LegacyDamageApiPresenceTest {
    @Test void currentDamageApiIsAvailableOnTheLegacyFork() {
        assertDoesNotThrow(() -> PlayerManager.class.getMethod("damageItemInHand",Player.class,int.class));
    }
}

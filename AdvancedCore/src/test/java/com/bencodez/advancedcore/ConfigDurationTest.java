package com.bencodez.advancedcore;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ConfigDurationTest {
    @Test void parsesLegacyAndModernUnits() {
        assertEquals(100L, ConfigDuration.parseMillis(100));
        assertEquals(1500L, ConfigDuration.parseMillis("1.5s"));
        assertEquals(1, ConfigDuration.readInt("30s", 60000L, 0));
        assertEquals(24d, ConfigDuration.read("24h",3600000d,0));
        assertEquals(24d, ConfigDuration.read(24,3600000d,0));
        assertEquals(0d, ConfigDuration.read(null,1d,0d));
    }

    @Test void rejectsMalformedNegativeAndOverflow() {
        assertThrows(IllegalArgumentException.class, () -> ConfigDuration.parseMillis("1"));
        assertThrows(IllegalArgumentException.class, () -> ConfigDuration.parseMillis("-1s"));
        assertThrows(IllegalArgumentException.class, () -> ConfigDuration.parseMillis("999999999999999999999d"));
    }
}

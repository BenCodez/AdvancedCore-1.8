package com.bencodez.advancedcore.api.rewards;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class LegacyRewardOptionsCopyTest {
    @Test void dispatchCopyPreservesAllLegacyFlagsAndOwnsItsPlaceholderMap() {
        RewardOptions original=new RewardOptions().setCheckRepeat(false).setCheckTimed(false).forceOffline()
            .setGiveOffline(false).setIgnoreChance(true).setIgnoreRequirements(true).setOnline(false)
            .setPrefix("prefix").setSuffix("suffix").setServer("backend").disableDefaultWorlds().orginalTrigger(123)
            .addPlaceholder("token","original");
        RewardOptions copy=original.copyForDispatch();assertNotSame(original,copy);assertNotSame(original.getPlaceholders(),copy.getPlaceholders());
        assertFalse(copy.isCheckRepeat());assertFalse(copy.isCheckTimed());assertTrue(copy.isForceOffline());assertFalse(copy.isGiveOffline());
        assertTrue(copy.isIgnoreChance());assertTrue(copy.isIgnoreRequirements());assertFalse(copy.isOnline());assertTrue(copy.isOnlineSet());
        assertEquals("prefix",copy.getPrefix());assertEquals("suffix",copy.getSuffix());assertEquals("backend",copy.getServer());
        assertFalse(copy.isUseDefaultWorlds());assertEquals(123,copy.getOrginalTrigger());
        original.addPlaceholder("token","changed");copy.addPlaceholder("other","copy");assertEquals("original",copy.getPlaceholders().get("token"));assertFalse(original.getPlaceholders().containsKey("other"));
        RewardOptions defaults=new RewardOptions().copyForDispatch();assertTrue(defaults.isCheckRepeat());assertFalse(defaults.isOnlineSet());assertTrue(defaults.isOnline());
    }
}

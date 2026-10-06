package com.bencodez.advancedcore.api.rewards;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import com.bencodez.advancedcore.AdvancedCorePlugin;

class LegacyRewardResolutionTest {
    @Test void directlyDefinedRewardWinsOverSameNamedEmptyFile() {
        withHandler(handler -> {
            DirectlyDefinedReward direct = mock(DirectlyDefinedReward.class);
            Reward configured = mock(Reward.class);
            Reward file = mock(Reward.class);
            when(direct.getPath()).thenReturn("VoteSites.Test.Rewards");
            when(direct.getReward()).thenReturn(configured);
            when(file.getName()).thenReturn("VoteSites_Test_Rewards");
            when(handler.getDirectlyDefinedRewards()).thenReturn(new ArrayList<>(Collections.singletonList(direct)));
            when(handler.getSubDirectlyDefinedRewards()).thenReturn(new ArrayList<>());
            when(handler.getRewards()).thenReturn(Collections.singletonList(file));
            assertSame(configured, handler.getReward("VoteSites_Test_Rewards"));
            verify(file, never()).getName();
        });
    }
    @Test void mixedUnderscoreAndDottedSubRewardsResolveAcrossAllLookupPaths() {
        withHandler(handler -> {
            SubDirectlyDefinedReward direct = mock(SubDirectlyDefinedReward.class);
            Reward configured = mock(Reward.class);
            when(direct.getFullPath()).thenReturn("Vote_Sites.Test.Rewards.Random.0");
            when(direct.getReward()).thenReturn(configured);
            when(handler.getDirectlyDefinedRewards()).thenReturn(new ArrayList<>());
            when(handler.getSubDirectlyDefinedRewards()).thenReturn(new ArrayList<>(Collections.singletonList(direct)));
            when(handler.getRewards()).thenReturn(Collections.emptyList());
            String serialized = "vote_sites_test_rewards_random_0";
            assertSame(configured, handler.getReward(serialized));
            assertSame(direct, handler.getSubDirectlyDefined(serialized));
            assertTrue(handler.hasDirectRewardHandle(serialized));
            assertSame(direct, handler.getSubDirectlyDefined("Vote_Sites.Test.Rewards.Random.0"));
            assertFalse(handler.hasDirectRewardHandle("Vote_Sites.Test.Rewards.Random.1"));
        });
    }
    private void withHandler(java.util.function.Consumer<RewardHandler> test) {
        AdvancedCorePlugin plugin = mock(AdvancedCorePlugin.class);
        when(plugin.getDataFolder()).thenReturn(new File(System.getProperty("java.io.tmpdir"), "legacy-reward-fixture"));
        try (MockedStatic<AdvancedCorePlugin> global = mockStatic(AdvancedCorePlugin.class)) {
            global.when(AdvancedCorePlugin::getInstance).thenReturn(plugin);
            RewardHandler handler = mock(RewardHandler.class, CALLS_REAL_METHODS);
            handler.plugin = plugin;
            try { test.accept(handler); }
            finally { RewardHandler.getInstance().getRepeatTimer().cancel(); }
        }
    }
}

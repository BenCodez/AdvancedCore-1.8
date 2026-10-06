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
    @Test void directAliasesAreCaseInsensitiveAndLocaleIndependentAcrossLookupPaths() {
        withHandler(handler -> {
            DirectlyDefinedReward direct=mock(DirectlyDefinedReward.class);Reward resolved=mock(Reward.class);
            when(direct.getPath()).thenReturn("Vote Sites.Item.Rewards");when(direct.getReward()).thenReturn(resolved);
            when(handler.getDirectlyDefinedRewards()).thenReturn(new ArrayList<>(Collections.singletonList(direct)));
            when(handler.getSubDirectlyDefinedRewards()).thenReturn(new ArrayList<>());
            when(handler.getRewards()).thenReturn(Collections.emptyList());
            java.util.Locale previous=java.util.Locale.getDefault();
            try {java.util.Locale.setDefault(new java.util.Locale("tr","TR"));
                assertSame(direct,handler.getDirectlyDefined("vote_sites_item_rewards"));
                assertSame(resolved,handler.getReward("vote_sites_item_rewards"));
                assertTrue(handler.hasDirectRewardHandle("vote sites.item.rewards"));
            }finally {java.util.Locale.setDefault(previous);}
        });
    }
    @Test void duplicateSubAliasesDoNotRegisterTwice() {
        withHandler(handler -> {
            SubDirectlyDefinedReward first=mock(SubDirectlyDefinedReward.class),duplicate=mock(SubDirectlyDefinedReward.class);
            when(first.getFullPath()).thenReturn("Vote Sites.Item.Rewards.Random.0");
            when(duplicate.getFullPath()).thenReturn("vote_sites_item_rewards_random_0");
            ArrayList<SubDirectlyDefinedReward> registered=new ArrayList<>();
            when(handler.getSubDirectlyDefinedRewards()).thenReturn(registered);
            try {java.lang.reflect.Field field=RewardHandler.class.getDeclaredField("subDirectlyDefinedRewards");
                field.setAccessible(true);field.set(handler,registered);
            }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
            handler.addSubDirectlyDefined(first);handler.addSubDirectlyDefined(duplicate);
            assertEquals(Collections.singletonList(first),registered);
        });
    }
    @Test void rewardExistNormalizesSpacesAndAcceptsNullWithoutCreatingFiles() {
        withHandler(handler -> {
            Reward reward=mock(Reward.class);when(reward.getName()).thenReturn("Vote_Site");
            when(handler.getRewards()).thenReturn(Collections.singletonList(reward));
            assertTrue(handler.rewardExist("vote site"));assertFalse(handler.rewardExist(null));
        });
    }
    @Test void unsafeMissingRewardNamesAreRejectedBeforeConstructingFiles() {
        withHandler(handler -> {
            when(handler.getDirectlyDefinedRewards()).thenReturn(new ArrayList<>());
            when(handler.getSubDirectlyDefinedRewards()).thenReturn(new ArrayList<>());
            when(handler.getRewards()).thenReturn(Collections.emptyList());
            when(handler.getDefaultFolder()).thenReturn(new File(System.getProperty("java.io.tmpdir"),"guarded-rewards"));
            try(org.mockito.MockedConstruction<Reward> rewards=mockConstruction(Reward.class)) {
                for(String name:new String[]{"../outside","/absolute","folder/name","folder\\name","invalid\0name"}) {
                    assertThrows(IllegalArgumentException.class,()->handler.getReward(name));
                    assertThrows(IllegalArgumentException.class,()->handler.getRewardDirectlyDefined(name));
                }
                assertTrue(rewards.constructed().isEmpty());
            }
        });
    }
    @Test void directRegistrationDeduplicatesNormalizedAliases() {
        withHandler(handler -> {
            DirectlyDefinedReward first=mock(DirectlyDefinedReward.class),second=mock(DirectlyDefinedReward.class);
            when(first.getPath()).thenReturn("Vote Sites.Item.Rewards");when(second.getPath()).thenReturn("vote_sites_item_rewards");
            ArrayList<DirectlyDefinedReward> registered=new ArrayList<>();when(handler.getDirectlyDefinedRewards()).thenReturn(registered);
            try {java.lang.reflect.Field field=RewardHandler.class.getDeclaredField("directlyDefinedRewards");
                field.setAccessible(true);field.set(handler,registered);
            }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
            handler.addDirectlyDefined(first);handler.addDirectlyDefined(second);
            assertEquals(Collections.singletonList(first),registered);
        });
    }
    @Test void registeredHandleWithSeparatorResolvesWithoutFilesystemFallback() {
        withHandler(handler -> {
            DirectlyDefinedReward direct=mock(DirectlyDefinedReward.class);Reward resolved=mock(Reward.class);
            when(direct.getPath()).thenReturn("Registered/Reward");when(direct.getReward()).thenReturn(resolved);
            when(handler.getDirectlyDefinedRewards()).thenReturn(new ArrayList<>(Collections.singletonList(direct)));
            when(handler.getSubDirectlyDefinedRewards()).thenReturn(new ArrayList<>());when(handler.getRewards()).thenReturn(Collections.emptyList());
            assertSame(resolved,handler.getReward("registered/reward"));
        });
    }
    @Test void ordinaryMissingRewardStillUsesNormalizedLegacyFileFallback() {
        withHandler(handler -> {
            when(handler.getDirectlyDefinedRewards()).thenReturn(new ArrayList<>());
            when(handler.getSubDirectlyDefinedRewards()).thenReturn(new ArrayList<>());when(handler.getRewards()).thenReturn(Collections.emptyList());
            java.util.List<String> names=new ArrayList<>();
            try(org.mockito.MockedConstruction<Reward> constructed=mockConstruction(Reward.class,
                (mock,context)->names.add((String)context.arguments().get(0)))) {
                Reward resolved=handler.getReward("Daily Bonus");assertSame(constructed.constructed().get(0),resolved);
                assertEquals(Collections.singletonList("Daily_Bonus"),names);
            }
        });
    }
    private void withHandler(java.util.function.Consumer<RewardHandler> test) {
        AdvancedCorePlugin plugin = mock(AdvancedCorePlugin.class);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
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

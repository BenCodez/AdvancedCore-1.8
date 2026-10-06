package com.bencodez.advancedcore.api.rewards;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.MockedConstruction;
import com.bencodez.advancedcore.AdvancedCorePlugin;

class LegacyGeneratedRewardRegistryTest {
    @TempDir File directory;
    @Test void generatedSnapshotDoesNotBecomePublicRewardOrRunPublicValidation() {
        fixture(f->{
            Reward generated=f.reward("daily",new File(directory,"dIrEcTlYdEfInEd"),true);
            f.handler.updateReward(generated);assertTrue(f.rewards.isEmpty());verify(generated,never()).validate();
            try(MockedConstruction<Reward> fallback=mockConstruction(Reward.class)) {
                Reward ordinary=f.handler.getReward("daily");assertSame(fallback.constructed().get(0),ordinary);assertNotSame(generated,ordinary);
            }
        });
    }
    @Test void generatedSnapshotCannotShadowSameNamedOrdinaryReward() {
        fixture(f->{
            Reward ordinary=f.reward("daily",directory,false),generated=f.reward("daily",new File(directory,"DirectlyDefined"),true);
            f.handler.updateReward(ordinary);f.handler.updateReward(generated);
            assertEquals(Collections.singletonList(ordinary),f.rewards);assertSame(ordinary,f.handler.getReward("daily"));
        });
    }
    @Test void normalFileReplacementStillUsesBackingFileIdentity() {
        fixture(f->{
            Reward first=f.reward("daily",directory,false),replacement=f.reward("daily",directory,false);
            f.handler.updateReward(first);f.handler.updateReward(replacement);assertEquals(Collections.singletonList(replacement),f.rewards);
            verify(first).validate();verify(replacement).validate();
        });
    }
    @Test void exclusionRequiresBothGeneratedMarkerAndGeneratedFolder() {
        fixture(f->{
            Reward unmarked=f.reward("unmarked",new File(directory,"DirectlyDefined"),false),outside=f.reward("outside",directory,true),noFolder=f.reward("noFolder",null,true);
            f.handler.updateReward(unmarked);f.handler.updateReward(outside);f.handler.updateReward(noFolder);
            assertEquals(Arrays.asList(unmarked,outside,noFolder),f.rewards);verify(unmarked).validate();verify(outside).validate();verify(noFolder).validate();
        });
    }
    @Test void excludedSnapshotRemainsExplicitlyLoadableWithRequestedUserRestriction() throws Exception {
        File folder=new File(directory,"DirectlyDefined");assertTrue(folder.mkdir());Files.write(new File(folder,"daily.yml").toPath(),"DirectlyDefinedReward: true\nEXP: 7\n".getBytes(StandardCharsets.UTF_8));
        fixture(f->{
            f.handler.updateReward(f.reward("daily",folder,true));assertTrue(f.rewards.isEmpty());
            List<List<?>> arguments=new ArrayList<>();
            try(MockedConstruction<QueuedGeneratedReward> loaded=mockConstruction(QueuedGeneratedReward.class,(mock,context)->arguments.add(context.arguments()))) {
                Reward explicit=f.handler.getQueuedGeneratedReward("daily","requested-user");assertSame(loaded.constructed().get(0),explicit);
                assertEquals(Collections.singleton("requested-user"),arguments.get(0).get(2));
                assertEquals(7,((org.bukkit.configuration.ConfigurationSection)arguments.get(0).get(3)).getInt("EXP"));
            }
        });
    }
    @Test void absentConfigurationDoesNotChangeEstablishedOrdinaryValidationPath() {
        fixture(f->{
            Reward noConfig=mock(Reward.class);when(noConfig.getName()).thenReturn("legacy");when(noConfig.getFile()).thenReturn(new File(directory,"legacy.yml"));
            f.handler.updateReward(noConfig);assertEquals(Collections.singletonList(noConfig),f.rewards);verify(noConfig).validate();
            assertThrows(NullPointerException.class,()->f.handler.updateReward((Reward)null));
        });
    }
    private void fixture(Consumer<Fixture> test) {
        AdvancedCorePlugin plugin=mock(AdvancedCorePlugin.class);when(plugin.getDataFolder()).thenReturn(directory);when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        try(MockedStatic<AdvancedCorePlugin> global=mockStatic(AdvancedCorePlugin.class)) {
            global.when(AdvancedCorePlugin::getInstance).thenReturn(plugin);RewardHandler handler=mock(RewardHandler.class,CALLS_REAL_METHODS);handler.plugin=plugin;
            ArrayList<Reward> rewards=new ArrayList<>();when(handler.getRewards()).thenReturn(rewards);when(handler.getDirectlyDefinedRewards()).thenReturn(new ArrayList<>());when(handler.getSubDirectlyDefinedRewards()).thenReturn(new ArrayList<>());when(handler.getDefaultFolder()).thenReturn(directory);
            try{test.accept(new Fixture(handler,rewards));}finally{RewardHandler.getInstance().getRepeatTimer().cancel();}
        }
    }
    private class Fixture {
        final RewardHandler handler;final ArrayList<Reward> rewards;
        Fixture(RewardHandler handler,ArrayList<Reward> rewards){this.handler=handler;this.rewards=rewards;}
        Reward reward(String name,File folder,boolean generated) {
            Reward reward=mock(Reward.class);RewardFileData data=mock(RewardFileData.class);when(reward.getName()).thenReturn(name);when(reward.getConfig()).thenReturn(data);
            when(reward.getFile()).thenReturn(new File(folder==null?directory:folder,name+".yml"));when(data.isDirectlyDefinedReward()).thenReturn(generated);when(data.getRewardFolder()).thenReturn(folder);return reward;
        }
    }
}

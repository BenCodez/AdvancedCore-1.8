package com.bencodez.advancedcore.api.rewards;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import com.bencodez.advancedcore.AdvancedCorePlugin;

class LegacyGeneratedSnapshotCandidateTest {
    @TempDir Path directory;
    @Test void candidatePreservesLegacyMergeButDoesNotMutatePredecessorOrSource() throws Exception {
        fixture((old,target) -> {
            YamlConfiguration source=new YamlConfiguration();source.set("Commands.Console",java.util.Arrays.asList("say new"));source.set("Delayed.Enabled",true);source.set("Delayed.Seconds",3);
            byte[] predecessor=Files.readAllBytes(target);Reward candidate=old.getConfig().prepareGeneratedSnapshot(source);
            assertNotSame(old,candidate);assertNotSame(old.getConfig(),candidate.getConfig());assertArrayEquals(predecessor,Files.readAllBytes(target));
            assertEquals("old",old.getConfig().getConfigData().getString("Nested.Old"));assertFalse(old.getConfig().isDirectlyDefinedReward());
            assertTrue(candidate.getConfig().isDirectlyDefinedReward());assertEquals("old",candidate.getConfig().getConfigData().getString("Nested.Old"));assertTrue(candidate.isDelayEnabled());assertEquals(3,candidate.getDelaySeconds());
            assertEquals(old.getFile(),candidate.getFile());assertEquals(old.getConfig().getRewardFolder(),candidate.getConfig().getRewardFolder());
            source.set("Commands.Console",java.util.Arrays.asList("changed"));assertEquals(java.util.Arrays.asList("say new"),candidate.getConfig().getConfigData().getStringList("Commands.Console"));
            candidate.getConfig().getConfigData().set("Nested.Old","candidate only");assertEquals("old",old.getConfig().getConfigData().getString("Nested.Old"));
        });
    }
    @Test void failedPublicationLeavesRegisteredPredecessorStateAndBytesUntouched() throws Exception {
        fixture((old,target) -> {
            YamlConfiguration source=new YamlConfiguration();source.set("ForceOffline",true);Reward candidate=old.getConfig().prepareGeneratedSnapshot(source);
            byte[] malformed="Bad: [\n".getBytes(StandardCharsets.UTF_8);Files.write(target,malformed);
            assertThrows(IOException.class,()->candidate.getConfig().saveStrict());assertArrayEquals(malformed,Files.readAllBytes(target));assertFalse(old.isForceOffline());assertFalse(old.getConfig().isDirectlyDefinedReward());
        });
    }
    @Test void checkedCandidatePublicationCarriesHeaderFlagAndNestedValues() throws Exception {
        fixture((old,target) -> {
            YamlConfiguration source=new YamlConfiguration();source.set("ForceOffline",true);source.set("Nested.New",7);Reward candidate=old.getConfig().prepareGeneratedSnapshot(source);
            candidate.getConfig().saveStrict();YamlConfiguration read=new YamlConfiguration();read.load(target.toFile());assertTrue(read.getBoolean("DirectlyDefinedReward"));assertTrue(read.getBoolean("ForceOffline"));
            assertNull(read.getString("Nested.Old"));assertEquals(7,read.getInt("Nested.New"));assertEquals("retained",read.getString("Unrelated"));assertTrue(new String(Files.readAllBytes(target),StandardCharsets.UTF_8).contains("WRONG PLACE TO EDIT"));assertFalse(old.isForceOffline());
        });
    }
    private void fixture(CheckedTest test) throws Exception {
        AdvancedCorePlugin plugin=mock(AdvancedCorePlugin.class);when(plugin.getDataFolder()).thenReturn(directory.toFile());
        try(MockedStatic<AdvancedCorePlugin> global=mockStatic(AdvancedCorePlugin.class)) {
            global.when(AdvancedCorePlugin::getInstance).thenReturn(plugin);Path folder=directory.resolve("DirectlyDefined");Files.createDirectories(folder);Path target=folder.resolve("snapshot.yml");Files.write(target,"Nested:\n  Old: old\nUnrelated: retained\n".getBytes(StandardCharsets.UTF_8));
            Reward old=new Reward(folder.toFile(),"snapshot");test.run(old,target);
        }
    }
    private interface CheckedTest {void run(Reward old,Path path) throws Exception;}
}

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
    @Test void finalPublicationFailureDoesNotRunLegacyWritesOrMutateRegisteredPredecessor() throws Exception {
        fixture((old,target)->{
            AdvancedCorePlugin plugin=old.getConfig().plugin;RewardHandler handler=mock(RewardHandler.class);
            when(plugin.getRewardHandler()).thenReturn(handler);when(handler.getRewardDirectlyDefined("snapshot")).thenReturn(old);
            byte[] before=Files.readAllBytes(target);YamlConfiguration values=new YamlConfiguration();values.set("ForceOffline",true);values.set("Nested.New",7);
            Reward source=new Reward("snapshot",values);
            com.bencodez.advancedcore.api.misc.files.FilesManager writer=mock(com.bencodez.advancedcore.api.misc.files.FilesManager.class);
            doAnswer(call->{Files.write(((java.io.File)call.getArgument(0)).toPath(),((org.bukkit.configuration.file.FileConfiguration)call.getArgument(1)).saveToString().getBytes(StandardCharsets.UTF_8));return null;}).when(writer).editFile(any(),any());
            doThrow(new IOException("final snapshot publication failed")).when(writer).editFileStrict(any(),any());
            try(MockedStatic<com.bencodez.advancedcore.api.misc.files.FilesManager> files=mockStatic(com.bencodez.advancedcore.api.misc.files.FilesManager.class)) {
                files.when(com.bencodez.advancedcore.api.misc.files.FilesManager::getInstance).thenReturn(writer);
                assertThrows(IllegalStateException.class,source::checkRewardFile);
                assertFalse(old.isForceOffline());assertFalse(old.getConfig().isDirectlyDefinedReward());assertFalse(source.isGeneratedSnapshotCreated());
                assertArrayEquals(before,Files.readAllBytes(target));verify(writer,never()).editFile(any(),any());verify(handler,never()).updateReward(any());
            }
        });
    }
    @Test void publicationRegistersOnlyDetachedAcknowledgedCandidateAndRetainsMergeBehavior() throws Exception {
        fixture((old,target)->{
            AdvancedCorePlugin plugin=old.getConfig().plugin;RewardHandler handler=mock(RewardHandler.class);
            when(plugin.getRewardHandler()).thenReturn(handler);when(handler.getRewardDirectlyDefined("snapshot")).thenReturn(old);
            java.util.concurrent.atomic.AtomicReference<Reward> registered=new java.util.concurrent.atomic.AtomicReference<>();
            doAnswer(call->{Reward candidate=call.getArgument(0);assertNotSame(old,candidate);assertFalse(old.isForceOffline());
                YamlConfiguration persisted=new YamlConfiguration();persisted.load(target.toFile());assertTrue(persisted.getBoolean("ForceOffline"));assertTrue(persisted.getBoolean("DirectlyDefinedReward"));
                assertTrue(candidate.isGeneratedSnapshotCreated());registered.set(candidate);return null;}).when(handler).updateReward(any());
            YamlConfiguration values=new YamlConfiguration();values.set("ForceOffline",true);values.set("Nested.New",7);Reward source=new Reward("snapshot",values);
            com.bencodez.advancedcore.api.misc.files.FilesManager writer=mock(com.bencodez.advancedcore.api.misc.files.FilesManager.class,CALLS_REAL_METHODS);
            doThrow(new AssertionError("Legacy per-key publication must not run")).when(writer).editFile(any(),any());
            try(MockedStatic<com.bencodez.advancedcore.api.misc.files.FilesManager> files=mockStatic(com.bencodez.advancedcore.api.misc.files.FilesManager.class)) {
                files.when(com.bencodez.advancedcore.api.misc.files.FilesManager::getInstance).thenReturn(writer);source.checkRewardFile();
                assertTrue(source.isGeneratedSnapshotCreated());assertTrue(registered.get().isForceOffline());assertFalse(old.isForceOffline());assertFalse(old.getConfig().isDirectlyDefinedReward());
                assertEquals("retained",registered.get().getConfig().getConfigData().getString("Unrelated"));assertNull(registered.get().getConfig().getConfigData().getString("Nested.Old"));assertEquals(7,registered.get().getConfig().getConfigData().getInt("Nested.New"));
                verify(writer,times(1)).editFileStrict(any(),any());verify(writer,never()).editFile(any(),any());
            }
        });
    }
    @Test void failedPublicationCanRetryWithoutUsingAChangedPredecessor() throws Exception {
        fixture((old,target)->{
            AdvancedCorePlugin plugin=old.getConfig().plugin;RewardHandler handler=mock(RewardHandler.class);
            when(plugin.getRewardHandler()).thenReturn(handler);when(handler.getRewardDirectlyDefined("snapshot")).thenReturn(old);
            byte[] before=Files.readAllBytes(target);YamlConfiguration values=new YamlConfiguration();values.set("ForceOffline",true);Reward source=new Reward("snapshot",values);
            com.bencodez.advancedcore.api.misc.files.FilesManager writer=mock(com.bencodez.advancedcore.api.misc.files.FilesManager.class,CALLS_REAL_METHODS);
            doThrow(new IOException("first attempt unavailable")).doCallRealMethod().when(writer).editFileStrict(any(),any());
            doThrow(new AssertionError("Legacy per-key publication must not run")).when(writer).editFile(any(),any());
            try(MockedStatic<com.bencodez.advancedcore.api.misc.files.FilesManager> files=mockStatic(com.bencodez.advancedcore.api.misc.files.FilesManager.class)) {
                files.when(com.bencodez.advancedcore.api.misc.files.FilesManager::getInstance).thenReturn(writer);
                assertThrows(IllegalStateException.class,source::checkRewardFile);assertArrayEquals(before,Files.readAllBytes(target));assertFalse(old.isForceOffline());verify(handler,never()).updateReward(any());
                source.checkRewardFile();assertTrue(source.isGeneratedSnapshotCreated());assertFalse(old.isForceOffline());verify(handler,times(1)).updateReward(any());verify(writer,times(2)).editFileStrict(any(),any());
            }
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

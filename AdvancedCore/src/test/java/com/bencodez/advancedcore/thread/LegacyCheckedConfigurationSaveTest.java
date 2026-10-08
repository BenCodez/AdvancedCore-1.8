package com.bencodez.advancedcore.thread;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.PosixFilePermissions;
import java.io.IOException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class LegacyCheckedConfigurationSaveTest {
    @TempDir Path directory;
    @Test void writesReadableNestedDocumentWithoutStartingPollingThread() throws Exception {
        FileThread owner=mock(FileThread.class,CALLS_REAL_METHODS);Path target=directory.resolve("Rewards/snapshot.yml");
        YamlConfiguration config=new YamlConfiguration();config.options().header("generated snapshot");config.set("DirectlyDefinedReward",true);config.set("Commands.Console",java.util.Arrays.asList("say quoted ' value"));
        owner.saveConfigurationStrict(target.toFile(),config);YamlConfiguration read=new YamlConfiguration();read.load(target.toFile());
        assertTrue(read.getBoolean("DirectlyDefinedReward"));assertEquals(config.getStringList("Commands.Console"),read.getStringList("Commands.Console"));verify(owner,never()).getThread();assertNoStages();
    }
    @Test void atomicPublicationFailurePreservesPredecessorAndCleansStage() throws Exception {
        FileThread owner=mock(FileThread.class,CALLS_REAL_METHODS);Path target=directory.resolve("reward.yml");byte[] old="Preserved: before\n".getBytes(StandardCharsets.UTF_8);Files.write(target,old);
        doThrow(new AtomicMoveNotSupportedException("stage","target","test filesystem")).when(owner).publishStrict(any(),any());
        assertThrows(IOException.class,()->owner.saveConfigurationStrict(target.toFile(),new YamlConfiguration()));assertArrayEquals(old,Files.readAllBytes(target));assertNoStages();
    }
    @Test void serializationFailurePreservesPredecessorAndCleansStage() throws Exception {
        FileThread owner=mock(FileThread.class,CALLS_REAL_METHODS);Path target=directory.resolve("reward.yml");byte[] old="Preserved: before\n".getBytes(StandardCharsets.UTF_8);Files.write(target,old);
        YamlConfiguration data=mock(YamlConfiguration.class);doThrow(new IOException("serialization failed")).when(data).save(any(java.io.File.class));
        assertThrows(IOException.class,()->owner.saveConfigurationStrict(target.toFile(),data));assertArrayEquals(old,Files.readAllBytes(target));assertNoStages();
    }
    @Test void malformedAndNonFilePredecessorsAreNeverReplaced() throws Exception {
        FileThread owner=mock(FileThread.class,CALLS_REAL_METHODS);Path target=directory.resolve("reward.yml");byte[] bad="Commands: [\n".getBytes(StandardCharsets.UTF_8);Files.write(target,bad);
        assertThrows(IOException.class,()->owner.saveConfigurationStrict(target.toFile(),new YamlConfiguration()));assertArrayEquals(bad,Files.readAllBytes(target));Files.delete(target);Files.createDirectory(target);
        assertThrows(IOException.class,()->owner.saveConfigurationStrict(target.toFile(),new YamlConfiguration()));assertTrue(Files.isDirectory(target));assertNoStages();
    }
    @Test @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX) void replacementPreservesSymlinkAndPosixIdentity() throws Exception {
        FileThread owner=mock(FileThread.class,CALLS_REAL_METHODS);Path actual=directory.resolve("actual.yml"),link=directory.resolve("reward.yml");Files.write(actual,"Value: before\n".getBytes(StandardCharsets.UTF_8));Files.createSymbolicLink(link,actual);
        java.util.Set<java.nio.file.attribute.PosixFilePermission> mode=PosixFilePermissions.fromString("rw-r-----");Files.setPosixFilePermissions(actual,mode);java.nio.file.attribute.PosixFileAttributes before=Files.readAttributes(actual,java.nio.file.attribute.PosixFileAttributes.class);
        YamlConfiguration data=new YamlConfiguration();data.set("Value","after");owner.saveConfigurationStrict(link.toFile(),data);
        assertTrue(Files.isSymbolicLink(link));assertEquals(mode,Files.getPosixFilePermissions(actual));assertEquals(before.owner(),Files.getOwner(actual));assertEquals(before.group(),Files.readAttributes(actual,java.nio.file.attribute.PosixFileAttributes.class).group());assertNoStages();
    }
    @Test void rewardConfigurationUsesTheCheckedOwnerAndRejectsMissingBackingDocument() throws Exception {
        try(org.mockito.MockedStatic<com.bencodez.advancedcore.AdvancedCorePlugin> global=mockStatic(com.bencodez.advancedcore.AdvancedCorePlugin.class)) {
            global.when(com.bencodez.advancedcore.AdvancedCorePlugin::getInstance).thenReturn(mock(com.bencodez.advancedcore.AdvancedCorePlugin.class));
            com.bencodez.advancedcore.api.rewards.RewardFileData reward=new com.bencodez.advancedcore.api.rewards.RewardFileData(mock(com.bencodez.advancedcore.api.rewards.Reward.class),new YamlConfiguration());
            assertThrows(IOException.class,reward::saveStrict);
            Path target=directory.resolve("snapshot.yml");reward.setDataFile(target.toFile());YamlConfiguration data=new YamlConfiguration();data.set("DirectlyDefinedReward",true);
            reward.saveStrict(data);YamlConfiguration stored=new YamlConfiguration();stored.load(target.toFile());assertTrue(stored.getBoolean("DirectlyDefinedReward"));
            byte[] malformed="Bad: [\n".getBytes(StandardCharsets.UTF_8);Files.write(target,malformed);
            assertThrows(IOException.class,()->reward.saveStrict(data));assertArrayEquals(malformed,Files.readAllBytes(target));assertNoStages();
        }
    }
    private void assertNoStages() throws Exception {try(java.util.stream.Stream<Path> paths=Files.walk(directory)){assertFalse(paths.anyMatch(p->p.getFileName().toString().startsWith(".configuration-")));}}
}

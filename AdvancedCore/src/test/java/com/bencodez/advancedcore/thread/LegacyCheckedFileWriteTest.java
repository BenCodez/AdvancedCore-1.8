package com.bencodez.advancedcore.thread;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.simpleapi.sql.data.*;

class LegacyCheckedFileWriteTest {
    @TempDir Path directory;
    private static final String UUID="00000000-0000-0000-0000-000000000001";
    @Test void createsOneTypedBatchWithoutStartingThePollingThread() throws Exception {
        FileThread owner=owner();Map<String,DataValue> values=new HashMap<>();
        values.put("Points",new DataValueInt(5));values.put("Message",new DataValueString("quoted ' text\nnext line"));
        values.put("Enabled",new DataValueBoolean(true));owner.setValuesStrict(UUID,values);
        YamlConfiguration data=read(file());assertEquals(5,data.getInt("Points"));
        assertEquals("quoted ' text\nnext line",data.getString("Message"));assertEquals("true",data.getString("Enabled"));
        verify(owner,never()).getThread();assertNoStagedFiles();
    }
    @Test void existingNestedAndSerializedDataSurvivesPartialUpdate() throws Exception {
        FileThread owner=owner();Files.createDirectories(file().getParent());
        Files.write(file(),"Points: 1\nNested:\n  preserved: value\nOfflineRewards: '{\"reward\":\"example\"}'\n".getBytes(StandardCharsets.UTF_8));
        owner.setValuesStrict(UUID,Collections.singletonMap("Points",new DataValueInt(7)));
        YamlConfiguration data=read(file());assertEquals(7,data.getInt("Points"));
        assertEquals("value",data.getString("Nested.preserved"));assertEquals("{\"reward\":\"example\"}",data.getString("OfflineRewards"));
        assertNoStagedFiles();
    }
    @Test void malformedExistingFileRemainsByteForByteUnchanged() throws Exception {
        FileThread owner=owner();Files.createDirectories(file().getParent());byte[] damaged="Points: [\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file(),damaged);assertThrows(IOException.class,
            ()->owner.setValuesStrict(UUID,Collections.singletonMap("Points",new DataValueInt(7))));
        assertArrayEquals(damaged,Files.readAllBytes(file()));assertNoStagedFiles();
    }
    @Test void failedPublicationPreservesOldDataAndCleansOnlyItsStage() throws Exception {
        FileThread owner=owner();Files.createDirectories(file().getParent());byte[] old="Points: 1\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file(),old);IOException failed=new IOException("publication rejected");
        doThrow(failed).when(owner).publishStrict(any(Path.class),any(Path.class));
        assertSame(failed,assertThrows(IOException.class,
            ()->owner.setValuesStrict(UUID,Collections.singletonMap("Points",new DataValueInt(7)))));
        assertArrayEquals(old,Files.readAllBytes(file()));assertNoStagedFiles();
    }
    @Test void invalidIdentityAndInvalidValuesCannotCreateAUserFile() throws Exception {
        FileThread owner=owner();assertThrows(IllegalArgumentException.class,
            ()->owner.setValuesStrict("../escape",Collections.singletonMap("Points",new DataValueInt(7))));
        assertThrows(IllegalArgumentException.class,()->owner.setValuesStrict(UUID,Collections.singletonMap("Points",null)));
        owner.setValuesStrict("invalid",Collections.emptyMap());assertFalse(Files.exists(directory.resolve("Data")));
    }
    @Test void directoryAtUserPathCannotBecomeAnEmptyFile() throws Exception {
        FileThread owner=owner();Files.createDirectories(file());
        assertThrows(IOException.class,()->owner.setValuesStrict(UUID,Collections.singletonMap("Points",new DataValueInt(7))));
        assertTrue(Files.isDirectory(file()));assertNoStagedFiles();
    }
    @Test @EnabledOnOs(OS.LINUX) void existingPosixModeIsPreserved() throws Exception {
        FileThread owner=owner();Files.createDirectories(file().getParent());Files.write(file(),"Points: 1\n".getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(file(),PosixFilePermissions.fromString("rw-r-----"));
        owner.setValuesStrict(UUID,Collections.singletonMap("Points",new DataValueInt(7)));
        assertEquals(PosixFilePermissions.fromString("rw-r-----"),Files.getPosixFilePermissions(file()));
    }
    @Test @EnabledOnOs(OS.LINUX) void existingSymlinkTargetKeepsItsLocationAndData() throws Exception {
        FileThread owner=owner();Files.createDirectories(file().getParent());Path actual=directory.resolve("linked-user.yml");
        Files.write(actual,"Points: 1\nPreserved: value\n".getBytes(StandardCharsets.UTF_8));Files.createSymbolicLink(file(),actual);
        owner.setValuesStrict(UUID,Collections.singletonMap("Points",new DataValueInt(7)));
        assertTrue(Files.isSymbolicLink(file()));assertEquals(7,read(actual).getInt("Points"));
        assertEquals("value",read(actual).getString("Preserved"));
    }
    @Test void checkedReadIsReadOnlyAndPreservesLegacyTypes() throws Exception {
        FileThread owner=owner();assertTrue(owner.getValuesStrict(UUID).isEmpty());assertFalse(Files.exists(directory.resolve("Data")));
        Files.createDirectories(file().getParent());byte[] data="Points: 7\nEnabled: 'true'\nMessage: text\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file(),data);Map<String,DataValue> read=owner.getValuesStrict(UUID);
        assertEquals(7,read.get("Points").getInt());assertEquals("true",read.get("Enabled").getString());
        assertEquals("text",read.get("Message").getString());assertArrayEquals(data,Files.readAllBytes(file()));verify(owner,never()).getThread();
    }
    @Test void checkedReadRejectsMalformedAndNonFileInputWithoutWriting() throws Exception {
        FileThread owner=owner();Files.createDirectories(file().getParent());byte[] bad="Points: [\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file(),bad);assertThrows(IOException.class,()->owner.getValuesStrict(UUID));assertArrayEquals(bad,Files.readAllBytes(file()));
        Files.delete(file());Files.createDirectories(file());assertThrows(IOException.class,()->owner.getValuesStrict(UUID));
        assertThrows(IllegalArgumentException.class,()->owner.getValuesStrict("../escape"));
    }
    private FileThread owner() throws Exception {
        FileThread owner=mock(FileThread.class,CALLS_REAL_METHODS);AdvancedCorePlugin plugin=mock(AdvancedCorePlugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());Field field=FileThread.class.getDeclaredField("plugin");
        field.setAccessible(true);field.set(owner,plugin);return owner;
    }
    private Path file() {return directory.resolve("Data").resolve(UUID+".yml");}
    private YamlConfiguration read(Path path)throws Exception {YamlConfiguration yaml=new YamlConfiguration();yaml.load(path.toFile());return yaml;}
    private void assertNoStagedFiles()throws Exception {
        try(java.util.stream.Stream<Path> paths=Files.list(file().getParent())) {
            assertEquals(0,paths.filter(path->path.getFileName().toString().startsWith(".user-data-")).count());
        }
    }
}

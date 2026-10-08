package com.bencodez.advancedcore.tests.proxy;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.bencodez.simpleapi.file.BungeeJsonFile;

class LegacyBungeeJsonCacheTest {
    @TempDir Path folder;
    @Test void existingCacheLoadsAndSurvivesSaveReloadWithLegacyGson() throws Exception {
        Path file = folder.resolve("votecache.json");
        Files.write(file, "{\"VoteCache\":{\"lobby\":{\"0\":{\"Name\":\"OfflinePlayer\",\"Time\":1234567890123}}}}".getBytes(StandardCharsets.UTF_8));
        BungeeJsonFile cache = new BungeeJsonFile(file.toFile());
        assertEquals("OfflinePlayer",cache.getString("VoteCache.lobby.0.Name","missing"));
        assertEquals(1234567890123L,cache.getLong("VoteCache.lobby.0.Time",0));
        cache.setStringList("CacheMetadata.Players",Arrays.asList("OfflinePlayer","SecondPlayer"));
        cache.setBoolean("CacheMetadata.Completed",true); cache.save();
        cache.reload();
        BungeeJsonFile restarted = new BungeeJsonFile(file.toFile());
        assertEquals(Arrays.asList("OfflinePlayer","SecondPlayer"),restarted.getStringList("CacheMetadata.Players",null));
        assertTrue(restarted.getBoolean("CacheMetadata.Completed",false));
        assertEquals("OfflinePlayer",restarted.getString("VoteCache.lobby.0.Name","missing"));
    }
    @Test void freshCacheCanBeSavedThenReloaded() {
        BungeeJsonFile cache = new BungeeJsonFile(folder.resolve("fresh.json").toFile());
        cache.setInt("VoteParty.Current",3); cache.save(); cache.reload();
        assertEquals(3,cache.getInt("VoteParty.Current",0));
    }
    @Test void nonObjectCacheStopsInitializationWithoutOverwritingRetainedBytes() throws Exception {
        for (String original : Arrays.asList("", "null", "42", "[]")) {
            Path file = folder.resolve("invalid-cache.json");
            byte[] bytes = original.getBytes(StandardCharsets.UTF_8);
            Files.write(file, bytes);
            assertThrows(IllegalStateException.class, () -> new BungeeJsonFile(file.toFile()));
            assertArrayEquals(bytes, Files.readAllBytes(file));
        }
    }

}

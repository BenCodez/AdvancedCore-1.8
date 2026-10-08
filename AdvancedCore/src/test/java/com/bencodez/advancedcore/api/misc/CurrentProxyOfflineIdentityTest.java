package com.bencodez.advancedcore.api.misc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.AdvancedCoreConfigOptions;

class CurrentProxyOfflineIdentityTest {
    @Test void legacyPolicyPreservesExistingCaseSensitiveIdentity() { check(false); }
    @Test void currentProxyPolicyUsesCanonicalCaseInsensitiveOfflineIdentity() { check(true); }
    private void check(boolean current) {
        PlayerManager manager=PlayerManager.getInstance(); AdvancedCorePlugin previous=manager.plugin;
        AdvancedCorePlugin plugin=mock(AdvancedCorePlugin.class); AdvancedCoreConfigOptions options=new AdvancedCoreConfigOptions();
        options.setOnlineMode(false); options.setCaseInsensitiveOfflineUuids(current); when(plugin.getOptions()).thenReturn(options);
        manager.plugin=plugin;
        try {
            String expected=UUID.nameUUIDFromBytes(("OfflinePlayer:"+(current?"alex":"Alex")).getBytes(StandardCharsets.UTF_8)).toString();
            assertEquals(expected,manager.getUUID("Alex"));
            if(current) assertEquals(manager.getUUID("Alex"),manager.getUUID("ALEX"));
            else assertNotEquals(manager.getUUID("Alex"),manager.getUUID("ALEX"));
        } finally { manager.plugin=previous; }
    }
}

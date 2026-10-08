package com.bencodez.advancedcore;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import com.bencodez.simpleapi.file.YMLConfig;
import com.bencodez.advancedcore.api.user.userstorage.mysql.MySQL;

class ModernConfigurationOptionsTest {
    private AdvancedCoreConfigOptions options(YamlConfiguration yaml) {
        YMLConfig file=mock(YMLConfig.class); when(file.getData()).thenReturn(yaml);
        AdvancedCoreConfigOptions o=new AdvancedCoreConfigOptions(); o.setYmlConfig(file); return o;
    }
    @Test void modernDurationsAndJavascriptOptionsReachNativeOptionsWithoutRewriting() {
        YamlConfiguration y=new YamlConfiguration(); y.set("SpamClickTime","100ms");
        y.set("DelayLoginEvent","2s"); y.set("SkullLoadDelay","4s");
        y.set("JavascriptEngine.Enabled",false);y.set("JavascriptEngine.CommandEnabled",true);
        y.set("JavascriptEngine.AutoDownload",true);
        String before=y.saveToString(); AdvancedCoreConfigOptions o=options(y);o.load(null);
        assertEquals(100,o.getSpamClickTime());assertEquals(2000,o.getDelayLoginEvent());
        assertEquals(4000,o.getSkullLoadDelay());assertTrue(o.isDisableJavascript());
        assertTrue(o.isEnableJavascriptCommand());assertFalse(o.isAutoDownload());assertEquals(before,y.saveToString());
    }
    @Test void explicitLegacyJavascriptAndNumericMillisecondsKeepPrecedence() {
        YamlConfiguration y=new YamlConfiguration();y.set("DisableJavascript",false);y.set("JavascriptEngine.Enabled",false);
        y.set("EnableJavascriptCommand",false);y.set("JavascriptEngine.CommandEnabled",true);
        y.set("AutoDownload",false);y.set("JavascriptEngine.AutoDownload",true);y.set("SkullLoadDelay",250);
        AdvancedCoreConfigOptions o=options(y);o.load(null);
        assertFalse(o.isDisableJavascript());assertFalse(o.isEnableJavascriptCommand());assertFalse(o.isAutoDownload());assertEquals(250,o.getSkullLoadDelay());
    }
    @Test void absentDurationsHaveSafeDefaultsAndMalformedDurationsFail() {
        YamlConfiguration y=new YamlConfiguration();AdvancedCoreConfigOptions o=options(y);o.load(null);
        assertEquals(100,o.getSpamClickTime());assertEquals(0,o.getDelayLoginEvent());assertEquals(4000,o.getSkullLoadDelay());
        y.set("SpamClickTime","bad");assertThrows(IllegalArgumentException.class,()->o.load(null));
    }
    @Test void databaseAliasPreservesNativeSectionAndLegacyPrecedence() {
        for (boolean legacy:new boolean[]{false,true}) {
            YamlConfiguration y=new YamlConfiguration();ConfigurationSection modern=y.createSection("Database");modern.set("DbType","MYSQL");
            ConfigurationSection expected=legacy?y.createSection("MySQL"):modern;
            AdvancedCorePlugin plugin=mock(AdvancedCorePlugin.class,CALLS_REAL_METHODS);AdvancedCoreConfigOptions opts=options(y);when(plugin.getOptions()).thenReturn(opts);doReturn("Fixture").when(plugin).getName();
            AdvancedCorePlugin previous=AdvancedCorePlugin.getInstance();AdvancedCorePlugin.setInstance(plugin);
            AtomicReference<Object> seen=new AtomicReference<Object>();
            try(MockedConstruction<MySQL> constructors=mockConstruction(MySQL.class,(mock,ctx)->seen.set(ctx.arguments().get(2)))) {
                plugin.createMySQLProvider();assertSame(expected,seen.get());
            } finally {AdvancedCorePlugin.setInstance(previous);}
        }
    }
    @Test void unsupportedDatabaseTypeCannotSilentlyConnectAsMysql() {
        YamlConfiguration y=new YamlConfiguration();y.set("Database.DbType","POSTGRESQL");
        AdvancedCorePlugin plugin=mock(AdvancedCorePlugin.class,CALLS_REAL_METHODS);AdvancedCoreConfigOptions opts=options(y);when(plugin.getOptions()).thenReturn(opts);
        assertThrows(IllegalArgumentException.class,plugin::createMySQLProvider);
    }
}

package com.bencodez.advancedcore.tests.artifact;

import static org.junit.jupiter.api.Assertions.*;
import java.io.DataInputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

class Java8ArtifactIT {
    @Test
    void packagedRuntimeContainsOnlyJava8BaseClassesAndCompatiblePool() throws Exception {
        Path artifact = Paths.get(System.getProperty("advancedcore.jar"));
        assertTrue(Files.isRegularFile(artifact), "The package phase must produce the actual shaded artifact");
        int classCount = 0;
        try (JarFile jar = new JarFile(artifact.toFile())) {
            assertNotNull(jar.getJarEntry("com/bencodez/advancedcore/AdvancedCorePlugin.class"));
            assertNotNull(jar.getJarEntry("advancedcoreversion.yml"));
            assertNotNull(jar.getJarEntry("org/slf4j/impl/StaticLoggerBinder.class"),
                    "Hikari uses SLF4J 1.7; package its matching binding");
            assertNotNull(jar.getJarEntry("com/bencodez/simpleapi/folialib/impl/LegacySpigotImplementation.class"),
                    "Reflectively selected Spigot 1.8 scheduler must be retained");
            assertNotNull(jar.getJarEntry("com/bencodez/advancedcore/folialib/impl/LegacySpigotImplementation.class"));
            assertNotNull(jar.getJarEntry("com/bencodez/advancedcore/hikari/HikariDataSource.class"));
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                assertFalse(name.matches("META-INF/[^/]+\\.(SF|RSA|DSA)"), "Stale signature: " + name);
                // Java 8 ignores versioned entries in a multi-release JAR.
                if (!name.endsWith(".class") || name.startsWith("META-INF/versions/")) continue;
                try (DataInputStream in = new DataInputStream(jar.getInputStream(entry))) {
                    assertEquals(0xCAFEBABE, in.readInt(), name);
                    in.readUnsignedShort();
                    int major = in.readUnsignedShort();
                    assertTrue(major <= 52, name + " requires class version " + major);
                    classCount++;
                }
            }
        }
        assertTrue(classCount > 100, "The test must inspect the shaded JAR, not an empty placeholder");
        // JDBC is bootstrap-loaded on Java 8 and platform-loaded on modular JDKs.
        // Use its defining loader without admitting the test/application classpath.
        try (URLClassLoader loader = new URLClassLoader(new URL[] { artifact.toUri().toURL() },
                java.sql.Connection.class.getClassLoader())) {
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("org.junit.jupiter.api.Test"));
            Class<?> legacy = Class.forName("com.bencodez.simpleapi.servercomm.global.GlobalMessageProxyHandler", false, loader);
            assertNotNull(legacy.getMethod("sendMessage", String.class, String.class, String[].class));
            Class<?> config = Class.forName("com.bencodez.advancedcore.hikari.HikariConfig", true, loader);
            assertSame(loader, config.getClassLoader(), "Hikari must come from the packaged artifact");
            Object instance = config.getConstructor().newInstance();
            config.getMethod("setMaximumPoolSize", int.class).invoke(instance, 2);
            assertEquals(2, config.getMethod("getMaximumPoolSize").invoke(instance));
        }
    }
}

package dev.strataproxy.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ConfigPathResolverTest {
    @Test
    void explicitArgumentWins(@TempDir Path tempDir) {
        var explicit = tempDir.resolve("custom.yml");

        var resolved = ConfigPathResolver.resolve(new String[] {explicit.toString()}, Map.of(), tempDir);

        assertEquals(explicit, resolved);
    }

    @Test
    void usesWorkingDirectoryConfigWhenPresent(@TempDir Path tempDir) throws Exception {
        var config = tempDir.resolve("config").resolve("strataproxy.yml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "network: {}\n");

        var resolved = ConfigPathResolver.resolve(new String[0], Map.of(), tempDir);

        assertEquals(config.normalize(), resolved);
    }

    @Test
    void usesAppHomeConfigWhenWorkingDirectoryConfigIsMissing(@TempDir Path tempDir) throws Exception {
        var appHome = tempDir.resolve("app");
        var config = appHome.resolve("config").resolve("strataproxy.yml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "network: {}\n");

        var resolved = ConfigPathResolver.resolve(new String[0], Map.of("APP_HOME", appHome.toString()), tempDir);

        assertEquals(config.normalize(), resolved);
    }

    @Test
    void usesDetectedAppHomeWhenEnvironmentIsMissing(@TempDir Path tempDir) throws Exception {
        var appHome = tempDir.resolve("installed");
        var config = appHome.resolve("config").resolve("strataproxy.yml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "network: {}\n");

        var resolved = ConfigPathResolver.resolve(new String[0], Map.of(), tempDir, appHome);

        assertEquals(config.normalize(), resolved);
    }
}

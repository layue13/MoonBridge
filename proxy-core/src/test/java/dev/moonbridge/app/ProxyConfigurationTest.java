package dev.moonbridge.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.moonbridge.core.protocol.ProtocolVarInt;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProxyConfigurationTest {
    @Test
    void requiresAnExplicitAuthenticationMode() throws Exception {
        var config = Files.createTempFile("moonbridge-no-auth", ".yml");
        try {
            Files.writeString(config, "listen: \"127.0.0.1:25577\"\nbackends: []\n");
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                    () -> new ProxyConfigurationLoader().load(config));
        } finally {
            Files.deleteIfExists(config);
        }
    }

    @Test
    void allowsPluginOnlyServerRegistration() throws Exception {
        var config = Files.createTempFile("moonbridge-config", ".yml");
        try {
            Files.writeString(config, "listen: \"127.0.0.1:25577\"\nauthentication: OFFLINE\nbackends: []\n");
            assertEquals(0, new ProxyConfigurationLoader().load(config).backends().size());
            assertEquals(4096, new ProxyConfigurationLoader().load(config).maxConnections());
        } finally {
            Files.deleteIfExists(config);
        }
    }

    @Test
    void rejectsInvalidConnectionLimit() throws Exception {
        var config = Files.createTempFile("moonbridge-cap", ".yml");
        try {
            for (int invalid : new int[]{0, -1, 1_000_001}) {
                Files.writeString(config, "listen: \"127.0.0.1:25577\"\nauthentication: OFFLINE\n"
                        + "maxConnections: " + invalid + "\n");
                assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                        () -> new ProxyConfigurationLoader().load(config));
            }
        } finally {
            Files.deleteIfExists(config);
        }
    }

    @Test
    void offlinePublicBindRequiresExplicitOptIn() throws Exception {
        var config = Files.createTempFile("moonbridge-public-offline", ".yml");
        try {
            String publicOffline = "listen: \"0.0.0.0:25577\"\nauthentication: OFFLINE\n";
            Files.writeString(config, publicOffline);
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                    () -> new ProxyConfigurationLoader().load(config));
            Files.writeString(config, "listen: \"localhost:25577\"\nauthentication: OFFLINE\n");
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                    () -> new ProxyConfigurationLoader().load(config));
            Files.writeString(config, publicOffline + "allowOfflinePublicAccess: true\n");
            assertEquals(true, new ProxyConfigurationLoader().load(config).allowOfflinePublicAccess());
            Files.writeString(config, "listen: \"0.0.0.0:25577\"\nauthentication: ONLINE_BUNGEE\n");
            assertEquals(ProxyConfiguration.Authentication.ONLINE_BUNGEE,
                    new ProxyConfigurationLoader().load(config).authentication());
        } finally {
            Files.deleteIfExists(config);
        }
    }

    @Test
    void validateConfigChecksStaticBackendEndpoints() throws Exception {
        var config = Files.createTempFile("moonbridge-invalid-backend", ".yml");
        try {
            Files.writeString(config, """
                    listen: "127.0.0.1:25577"
                    authentication: OFFLINE
                    backends:
                      - name: lobby
                        address: "operator@127.0.0.1:25565"
                    """);
            assertEquals(1, ProxyMain.run(new String[]{"--validate-config", config.toString()}));
        } finally {
            Files.deleteIfExists(config);
        }
    }

    @Test
    void rejectsDuplicateStaticNames() {
        var first = new ProxyConfiguration.Backend("lobby", "127.0.0.1:25565", null);
        var second = new ProxyConfiguration.Backend("lobby", "127.0.0.1:25566", null);
        assertThrows(IllegalArgumentException.class,
                () -> new ProxyConfiguration("127.0.0.1:25577", ProxyConfiguration.Authentication.OFFLINE,
                        java.util.List.of(first, second)));
    }

    @Test
    void readsEnabledPluginSettingsFromConfiguration() throws Exception {
        var config = Files.createTempFile("moonbridge-plugins", ".yml");
        try {
            Files.writeString(config, """
                    listen: "127.0.0.1:25577"
                    authentication: OFFLINE
                    backends: []
                    plugins:
                      directory: "extensions"
                      enabled:
                        dev.example.DnsPlugin:
                          record: "_minecraft._tcp.example.net"
                    """);
            var plugins = new ProxyConfigurationLoader().load(config).plugins();
            assertEquals("extensions", plugins.directory());
            assertEquals(5, plugins.eventTimeoutSeconds());
            assertEquals("_minecraft._tcp.example.net",
                    plugins.enabled().get("dev.example.DnsPlugin").get("record"));
            assertThrows(UnsupportedOperationException.class,
                    () -> plugins.enabled().get("dev.example.DnsPlugin").put("record", "changed"));
        } finally {
            Files.deleteIfExists(config);
        }
    }

    @Test
    void readsAndBoundsInitialRoutingConfiguration() throws Exception {
        var config = Files.createTempFile("moonbridge-timeout", ".yml");
        try {
            String prefix = "listen: \"127.0.0.1:25577\"\nauthentication: OFFLINE\ninitialRouting:\n"
                    + "  servers: [lobby-a, lobby-b]\n  timeoutSeconds: ";
            Files.writeString(config, prefix + "30\n");
            var routing = new ProxyConfigurationLoader().load(config).initialRouting();
            assertEquals(java.util.List.of("lobby-a", "lobby-b"), routing.servers());
            assertEquals(30, routing.timeoutSeconds());
            Files.writeString(config, "listen: \"127.0.0.1:25577\"\nauthentication: OFFLINE\n");
            routing = new ProxyConfigurationLoader().load(config).initialRouting();
            assertTrue(routing.servers().isEmpty());
            assertEquals(15, routing.timeoutSeconds());
            for (int invalid : new int[]{0, 121}) {
                Files.writeString(config, prefix + invalid + "\n");
                assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                        () -> new ProxyConfigurationLoader().load(config));
            }
            Files.writeString(config, prefix.replace("[lobby-a, lobby-b]", "[lobby-a, lobby-a]") + "15\n");
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                    () -> new ProxyConfigurationLoader().load(config));
            Files.writeString(config, "listen: \"127.0.0.1:25577\"\nauthentication: OFFLINE\n"
                    + "plugins:\n  initialPlacementTimeoutSeconds: 15\n");
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                    () -> new ProxyConfigurationLoader().load(config),
                    "the old placement timeout setting is intentionally rejected");
        } finally {
            Files.deleteIfExists(config);
        }
    }

    @Test
    void initialRoutingValidatesFallbackNamesAndPreservesOrder() {
        var names = java.util.stream.IntStream.range(0, 16).mapToObj(i -> "server-" + i).toList();
        assertEquals(names, new ProxyConfiguration.InitialRouting(names, 15).servers());
        assertThrows(IllegalArgumentException.class,
                () -> new ProxyConfiguration.InitialRouting(java.util.Collections.nCopies(17, "server"), 15));
        assertThrows(IllegalArgumentException.class,
                () -> new ProxyConfiguration.InitialRouting(java.util.List.of("lobby", "lobby"), 15));
        assertThrows(IllegalArgumentException.class,
                () -> new ProxyConfiguration.InitialRouting(java.util.List.of(" "), 15));
        assertThrows(IllegalArgumentException.class,
                () -> new ProxyConfiguration.InitialRouting(java.util.List.of("x".repeat(129)), 15));
    }

    @Test
    void readsAndBoundsEventTimeout() throws Exception {
        var config = Files.createTempFile("moonbridge-event-timeout", ".yml");
        try {
            String prefix = "listen: \"127.0.0.1:25577\"\nauthentication: OFFLINE\nplugins:\n"
                    + "  directory: plugins\n  eventTimeoutSeconds: ";
            Files.writeString(config, prefix + "3\n");
            assertEquals(3, new ProxyConfigurationLoader().load(config).plugins().eventTimeoutSeconds());
            for (int invalid : new int[]{0, 31}) {
                Files.writeString(config, prefix + invalid + "\n");
                assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                        () -> new ProxyConfigurationLoader().load(config));
            }
        } finally {
            Files.deleteIfExists(config);
        }
    }

    @Test
    void resolvesRelativePluginDirectoryFromTheConfigLocation() throws Exception {
        Path config = Path.of("example", "config", "moonbridge.yml").toAbsolutePath();
        assertEquals(config.getParent().getParent().resolve("plugins"),
                ProxyMain.pluginDirectory(config, "../plugins"));
        Path absolute = Path.of("example", "custom-plugins").toAbsolutePath();
        assertEquals(absolute, ProxyMain.pluginDirectory(config, absolute.toString()));
    }

    @Test
    void defaultsAndValidatesStatusConfiguration() throws Exception {
        Path config = Files.createTempFile("moonbridge-status", ".yml");
        try {
            Files.writeString(config, "listen: \"127.0.0.1:25577\"\nauthentication: OFFLINE\n");
            var loader = new ProxyConfigurationLoader();
            var defaults = loader.load(config).status();
            assertEquals("MoonBridge", defaults.motd());
            assertEquals(100, defaults.maxPlayers());
            assertNull(defaults.icon());

            String base = "listen: \"127.0.0.1:25577\"\nauthentication: OFFLINE\nstatus:\n  motd: \"%s\"\n  maxPlayers: %d\n";
            Files.writeString(config, base.formatted("岛".repeat(1024), 1_000_000));
            var maxMotd = loader.load(config).status().motd();
            assertEquals(1024, maxMotd.codePointCount(0, maxMotd.length()));
            for (int invalid : new int[]{0, -1, 1_000_001}) {
                Files.writeString(config, base.formatted("ready", invalid));
                assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class, () -> loader.load(config));
            }
            Files.writeString(config, base.formatted("x".repeat(1025), 100));
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class, () -> loader.load(config));
        } finally {
            Files.deleteIfExists(config);
        }
    }

    @Test
    void validatesOptionalIconPathFormatDimensionsAndSize() throws Exception {
        Path directory = Files.createTempDirectory("moonbridge-status-icon");
        Path config = directory.resolve("moonbridge.yml");
        Path icon = directory.resolve("icon.png");
        var loader = new ProxyConfigurationLoader();
        try {
            String yaml = "listen: \"127.0.0.1:25577\"\nauthentication: OFFLINE\n"
                    + "status:\n  icon: \"%s\"\n";
            Files.writeString(config, yaml.formatted("icon.png"));
            Files.write(icon, new byte[]{1, 2, 3});
            var invalidConfig = loader.load(config);
            assertThrows(java.io.IOException.class, () -> loader.loadServerListStatus(invalidConfig, config));
            assertEquals(1, ProxyMain.run(new String[]{"--validate-config", config.toString()}));

            writePng(icon, 32, 64);
            assertThrows(java.io.IOException.class, () -> loader.loadServerListStatus(invalidConfig, config));
            Files.write(icon, new byte[64 * 1024 + 1]);
            assertThrows(java.io.IOException.class, () -> loader.loadServerListStatus(invalidConfig, config));

            Files.writeString(config, yaml.formatted("missing.png"));
            var missingConfig = loader.load(config);
            assertThrows(java.io.IOException.class, () -> loader.loadServerListStatus(missingConfig, config));
            Files.writeString(config, yaml.formatted(icon.toAbsolutePath().toString().replace("\\", "\\\\")));
            var absoluteConfig = loader.load(config);
            assertThrows(java.io.IOException.class, () -> loader.loadServerListStatus(absoluteConfig, config));

            writePng(icon, 64, 64);
            Files.writeString(config, yaml.formatted("icon.png"));
            var validConfig = loader.load(config);
            var status = loader.loadServerListStatus(validConfig, config);
            assertEquals(true, status.faviconDataUrl().startsWith("data:image/png;base64,"));
            assertEquals(0, ProxyMain.run(new String[]{"--validate-config", config.toString()}));
            ByteBuf body = status.encode(UnpooledByteBufAllocator.DEFAULT, 4);
            try {
                assertEquals(0, ProtocolVarInt.read(body));
                byte[] jsonBytes = new byte[ProtocolVarInt.read(body)];
                body.readBytes(jsonBytes);
                var response = new ObjectMapper().readTree(new String(jsonBytes, StandardCharsets.UTF_8));
                assertEquals(status.faviconDataUrl(), response.path("favicon").textValue());
                assertEquals(4, response.path("players").path("online").intValue());
            } finally {
                body.release();
            }
        } finally {
            Files.deleteIfExists(icon);
            Files.deleteIfExists(config);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    void loadsIndependentBackendSendAndReceiveNamespacesIncludingEmptySets() throws Exception {
        Path config = Files.createTempFile("moonbridge-backend-channel", ".yml");
        try {
            String yaml = """
                    listen: "127.0.0.1:25577"
                    authentication: OFFLINE
                    backends: []
                    backendChannel:
                      listen: "127.0.0.1:28081"
                      clients:
                        island-a:
                          backendName: island-a
                          keyId: primary
                          secret: "test-secret-at-least-thirty-two-bytes-long"
                          allowedHosts: ["127.0.0.1"]
                          allowedNamespaces: [islands]
                    """;
            Files.writeString(config, yaml);
            var channel = new ProxyConfigurationLoader().load(config).backendChannel();
            assertEquals(28081, channel.listenAddress().getPort());
            assertEquals("island-a", channel.clients().get("island-a").backendName());
            assertEquals(java.util.Set.of("islands"), channel.clients().get("island-a").allowedNamespaces());
            assertEquals(java.util.Set.of("islands"), channel.clients().get("island-a").allowedReceiveNamespaces(),
                    "receiving defaults to the sending namespace set");
            Files.writeString(config, yaml.replace("allowedNamespaces: [islands]", "allowedNamespaces: []"));
            var denyAll = new ProxyConfigurationLoader().load(config).backendChannel().clients().get("island-a");
            assertTrue(denyAll.allowedNamespaces().isEmpty());
            assertTrue(denyAll.allowedReceiveNamespaces().isEmpty(), "implicit receive set inherits the empty send set");
            Files.writeString(config, yaml.replace("allowedNamespaces: [islands]",
                    "allowedNamespaces: [islands]\n      allowedReceiveNamespaces: []"));
            var receiveNone = new ProxyConfigurationLoader().load(config).backendChannel().clients().get("island-a");
            assertEquals(java.util.Set.of("islands"), receiveNone.allowedNamespaces());
            assertTrue(receiveNone.allowedReceiveNamespaces().isEmpty(), "an explicit empty receive ACL is send-only");
        } finally {
            Files.deleteIfExists(config);
        }
    }

    private static void writePng(Path destination, int width, int height) throws Exception {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        ImageIO.write(image, "PNG", destination.toFile());
    }
}

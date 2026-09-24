package dev.strataproxy.app;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ProxyConfigurationTest {
    @Test
    void requiresAnExplicitAuthenticationMode() throws Exception {
        var config = Files.createTempFile("strataproxy-no-auth", ".yml");
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
        var config = Files.createTempFile("strataproxy-config", ".yml");
        try {
            Files.writeString(config, "listen: \"127.0.0.1:25577\"\nauthentication: OFFLINE\nbackends: []\n");
            assertEquals(0, new ProxyConfigurationLoader().load(config).backends().size());
        } finally {
            Files.deleteIfExists(config);
        }
    }

    @Test
    void rejectsDuplicateStaticNames() {
        var first = new ProxyConfiguration.Backend("lobby", "127.0.0.1:25565", null, 100);
        var second = new ProxyConfiguration.Backend("lobby", "127.0.0.1:25566", null, 100);
        assertThrows(IllegalArgumentException.class,
                () -> new ProxyConfiguration("127.0.0.1:25577", ProxyConfiguration.Authentication.OFFLINE,
                        java.util.List.of(first, second)));
    }

    @Test
    void readsEnabledPluginSettingsFromConfiguration() throws Exception {
        var config = Files.createTempFile("strataproxy-plugins", ".yml");
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
            assertEquals("_minecraft._tcp.example.net",
                    plugins.enabled().get("dev.example.DnsPlugin").get("record"));
            assertThrows(UnsupportedOperationException.class,
                    () -> plugins.enabled().get("dev.example.DnsPlugin").put("record", "changed"));
        } finally {
            Files.deleteIfExists(config);
        }
    }
}

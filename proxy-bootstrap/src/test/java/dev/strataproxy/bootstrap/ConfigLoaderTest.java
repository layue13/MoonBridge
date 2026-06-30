package dev.strataproxy.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConfigLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void parsesByteUnits() {
        assertEquals(8 * 1024 * 1024, ConfigLoader.parseBytes("8mb", 0));
        assertEquals(4096, ConfigLoader.parseBytes("4kb", 0));
        assertEquals(512, ConfigLoader.parseBytes("512", 0));
    }

    @Test
    void parsesAddressWithDefaultPort() {
        var address = ConfigLoader.parseAddress("127.0.0.1", 25565);

        assertEquals("127.0.0.1", address.getHostString());
        assertEquals(25565, address.getPort());
    }

    @Test
    void parsesDurationUnits() {
        assertEquals(250, ConfigLoader.parseDurationMillis("250ms", 0));
        assertEquals(5_000, ConfigLoader.parseDurationMillis("5s", 0));
        assertEquals(60_000, ConfigLoader.parseDurationMillis("1m", 0));
    }

    @Test
    void parsesPacketAnalysisThresholds() throws Exception {
        var config = tempDir.resolve("strataproxy.yml");
        Files.writeString(config, """
                packetAnalysis:
                  largePayloadWarnBytes: "3mb"
                  unknownChannelThrottleBytes: "384kb"
                  moddedHandshakeWarnBytes: "4mb"
                  customPayloadFloodMaxCount: 123
                  customPayloadFloodWindow: "7s"
                servers:
                  - name: "lobby-1"
                    address: "127.0.0.1:25565"
                    tags: ["lobby"]
                    capabilities: ["modern-forwarding"]
                """);

        var loaded = new ConfigLoader().load(config);

        assertEquals(3 * 1024 * 1024, loaded.proxy().packetAnalysis().largePayloadWarnBytes());
        assertEquals(384 * 1024, loaded.proxy().packetAnalysis().unknownChannelThrottleBytes());
        assertEquals(4 * 1024 * 1024, loaded.proxy().packetAnalysis().moddedHandshakeWarnBytes());
        assertEquals(123, loaded.proxy().packetAnalysis().customPayloadFloodMaxCount());
        assertEquals(java.time.Duration.ofSeconds(7), loaded.proxy().packetAnalysis().customPayloadFloodWindow());
    }

    @Test
    void parsesCompressionRewriteFlag() throws Exception {
        var config = tempDir.resolve("strataproxy.yml");
        Files.writeString(config, """
                compression:
                  mode: fixed
                  minThreshold: 1024
                  maxThreshold: 1024
                  cpuGuard: 0.5
                  rewriteEnabled: true
                  rewriteMaxEventLoopDelayMillis: 7
                servers:
                  - name: "lobby-1"
                    address: "127.0.0.1:25565"
                """);

        var loaded = new ConfigLoader().load(config);

        assertEquals("fixed", loaded.proxy().compression().mode());
        assertEquals(1024, loaded.proxy().compression().minThreshold());
        assertEquals(1024, loaded.proxy().compression().maxThreshold());
        assertTrue(loaded.proxy().compression().rewriteEnabled());
        assertEquals(7, loaded.proxy().compression().rewriteMaxEventLoopDelayMillis());
    }

    @Test
    void parsesAdminTlsConfig() throws Exception {
        var config = tempDir.resolve("strataproxy.yml");
        Files.writeString(config, """
                admin:
                  tls:
                    enabled: true
                    keyStorePath: "conf/admin.p12"
                    keyStorePassword: "changeit"
                    keyStoreType: "PKCS12"
                    trustStorePath: "conf/clients.p12"
                    trustStorePassword: "clientpass"
                    trustStoreType: "JKS"
                    clientAuth: true
                servers:
                  - name: "lobby-1"
                    address: "127.0.0.1:25565"
                """);

        var loaded = new ConfigLoader().load(config);

        var tls = loaded.proxy().admin().tls();
        assertTrue(tls.enabled());
        assertEquals("conf/admin.p12", tls.keyStorePath());
        assertEquals("changeit", tls.keyStorePassword());
        assertEquals("PKCS12", tls.keyStoreType());
        assertEquals("conf/clients.p12", tls.trustStorePath());
        assertEquals("clientpass", tls.trustStorePassword());
        assertEquals("JKS", tls.trustStoreType());
        assertTrue(tls.clientAuth());
    }

    @Test
    void explicitEmptyServersStayEmpty() throws Exception {
        var config = tempDir.resolve("strataproxy.yml");
        Files.writeString(config, """
                admin:
                  enabled: true
                servers: []
                """);

        var loaded = new ConfigLoader().load(config);

        assertTrue(loaded.servers().isEmpty());
    }

    @Test
    void staticServersFalseIgnoresConfiguredServerList() throws Exception {
        var config = tempDir.resolve("strataproxy.yml");
        Files.writeString(config, """
                registry:
                  staticServers: false
                servers:
                  - name: "ignored"
                    address: "127.0.0.1:25565"
                """);

        var loaded = new ConfigLoader().load(config);

        assertTrue(loaded.servers().isEmpty());
        assertEquals(false, loaded.proxy().registry().staticServers());
    }

    @Test
    void expandsEnvironmentPlaceholdersBeforeParsingYaml() throws Exception {
        var config = tempDir.resolve("strataproxy.yml");
        Files.writeString(config, """
                admin:
                  bearerToken: "${STRATAPROXY_ADMIN_TOKEN:fallback-token}"
                servers:
                  - name: "lobby-1"
                    address: "${STRATAPROXY_BACKEND_HOST}:25565"
                    metadata:
                      unset: "${STRATAPROXY_UNSET:default-value}"
                """);

        var loaded = new ConfigLoader().load(config, Map.of(
                "STRATAPROXY_ADMIN_TOKEN", "secret-token",
                "STRATAPROXY_BACKEND_HOST", "10.0.0.12"));

        assertEquals("secret-token", loaded.proxy().admin().bearerToken());
        assertEquals("10.0.0.12", loaded.servers().getFirst().address().getHostString());
        assertEquals("default-value", loaded.servers().getFirst().metadata().get("unset"));
    }

    @Test
    void missingEnvironmentPlaceholderExpandsToEmptyString() {
        assertEquals("token=", ConfigLoader.expandEnvironment("token=${MISSING}", Map.of()));
        assertEquals("${bad-name}", ConfigLoader.expandEnvironment("${bad-name}", Map.of()));
    }
}

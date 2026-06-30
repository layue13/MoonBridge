package dev.strataproxy.bootstrap;

import dev.strataproxy.api.server.ProtocolRange;
import dev.strataproxy.api.server.ServerCapability;
import dev.strataproxy.api.server.ServerDescriptor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConfigValidatorTest {
    @TempDir
    Path tempDir;

    @Test
    void acceptsDefaultConfig() {
        var result = new ConfigValidator().validate(ConfigLoader.LoadedProxyConfig.defaults());

        assertTrue(result.valid());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("bearerToken")));
    }

    @Test
    void rejectsUnsafeNetworkAndCompressionValues() {
        var config = new ProxyConfig(
                new InetSocketAddress("127.0.0.1", 25577),
                0,
                true,
                new ProxyConfig.NetworkConfig(64, 0, 1024, 1024, 10, 20, -1, -1, 0, false),
                new ProxyConfig.RegistryConfig(true, true, "data/registry.json", true, Duration.ofSeconds(5), Duration.ofSeconds(2), "bad-mode"),
                new ProxyConfig.CompressionConfig("unknown", 8192, 256, 1.5d),
                new ProxyConfig.PacketAnalysisConfig(-1, -1, -1, -1, Duration.ZERO),
                ProxyConfig.ObservabilityConfig.defaults(),
                new ProxyConfig.AdminConfig(false, new InetSocketAddress("127.0.0.1", 8080), ""));
        var loaded = new ConfigLoader.LoadedProxyConfig(config, List.of(server("one")));

        var result = new ConfigValidator().validate(loaded);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("maxFrameBytes")));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("writeBufferWatermark")));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("maxConnectionsPerAddress")));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("maxNewConnectionsPerSecond")));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("maxNewConnectionsPerAddressPerSecond")));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("compression.mode")));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("cpuGuard")));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("registry.healthCheckMode")));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("packetAnalysis.largePayloadWarnBytes")));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("packetAnalysis.unknownChannelThrottleBytes")));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("packetAnalysis.moddedHandshakeWarnBytes")));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("packetAnalysis.customPayloadFloodMaxCount")));
    }

    @Test
    void rejectsDuplicateServersAndBadCapacity() {
        var loaded = new ConfigLoader.LoadedProxyConfig(
                ProxyConfig.defaults(),
                List.of(server("duplicate"), server("duplicate"), badCapacityServer()));

        var result = new ConfigValidator().validate(loaded);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("duplicate server name")));
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("softCapacity")));
    }

    @Test
    void acceptsDynamicOnlyRegistryWhenAdminApiIsEnabled() {
        var loaded = new ConfigLoader.LoadedProxyConfig(ProxyConfig.defaults(), List.of());

        var result = new ConfigValidator().validate(loaded);

        assertTrue(result.valid());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("no static servers")));
    }

    @Test
    void rejectsUnauthenticatedAdminApiOnNonLoopbackBind() {
        var config = new ProxyConfig(
                new InetSocketAddress("0.0.0.0", 8080),
                0,
                true,
                ProxyConfig.NetworkConfig.defaults(),
                ProxyConfig.RegistryConfig.defaults(),
                ProxyConfig.CompressionConfig.defaults(),
                ProxyConfig.PacketAnalysisConfig.defaults(),
                ProxyConfig.ObservabilityConfig.defaults(),
                new ProxyConfig.AdminConfig(true, new InetSocketAddress("0.0.0.0", 8080), ""));
        var loaded = new ConfigLoader.LoadedProxyConfig(config, List.of(server("one")));

        var result = new ConfigValidator().validate(loaded);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("admin.bearerToken must not be blank")));
    }

    @Test
    void rejectsEmptyRegistryWhenAdminApiIsDisabled() {
        var config = new ProxyConfig(
                new InetSocketAddress("127.0.0.1", 25577),
                0,
                true,
                ProxyConfig.NetworkConfig.defaults(),
                ProxyConfig.RegistryConfig.defaults(),
                ProxyConfig.CompressionConfig.defaults(),
                ProxyConfig.PacketAnalysisConfig.defaults(),
                ProxyConfig.ObservabilityConfig.defaults(),
                new ProxyConfig.AdminConfig(false, new InetSocketAddress("127.0.0.1", 8080), ""));
        var loaded = new ConfigLoader.LoadedProxyConfig(config, List.of());

        var result = new ConfigValidator().validate(loaded);

        assertFalse(result.valid());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("admin API is disabled")));
    }

    @Test
    void validatesNativeFeatureOverrides() {
        var config = new ProxyConfig(
                new InetSocketAddress("127.0.0.1", 25577),
                0,
                true,
                ProxyConfig.NetworkConfig.defaults(),
                ProxyConfig.RegistryConfig.defaults(),
                ProxyConfig.CompressionConfig.defaults(),
                ProxyConfig.PacketAnalysisConfig.defaults(),
                ProxyConfig.ObservabilityConfig.defaults(),
                ProxyConfig.AdminConfig.defaults(),
                new ProxyConfig.NativeConfig(true, true, true, false, false, false, Set.of("not-a-feature"), Set.of("aes")));

        var invalid = new ConfigValidator().validate(new ConfigLoader.LoadedProxyConfig(config, List.of(server("one"))));

        assertFalse(invalid.valid());
        assertTrue(invalid.errors().stream().anyMatch(error -> error.contains("unknown feature")));

        var conflict = new ProxyConfig(
                new InetSocketAddress("127.0.0.1", 25577),
                0,
                true,
                ProxyConfig.NetworkConfig.defaults(),
                ProxyConfig.RegistryConfig.defaults(),
                ProxyConfig.CompressionConfig.defaults(),
                ProxyConfig.PacketAnalysisConfig.defaults(),
                ProxyConfig.ObservabilityConfig.defaults(),
                ProxyConfig.AdminConfig.defaults(),
                new ProxyConfig.NativeConfig(true, true, true, false, false, false, Set.of("aes"), Set.of("aes")));

        var conflictResult = new ConfigValidator().validate(new ConfigLoader.LoadedProxyConfig(conflict, List.of(server("one"))));

        assertFalse(conflictResult.valid());
        assertTrue(conflictResult.errors().stream().anyMatch(error -> error.contains("both disabled and forced")));
    }

    @Test
    void validatesForwardingModesAndSecrets() {
        var bungeeLegacy = validateWithForwarding(new ProxyConfig.ForwardingConfig("bungee-legacy", ""));
        assertTrue(bungeeLegacy.valid());

        var missingBungeeGuardSecret = validateWithForwarding(new ProxyConfig.ForwardingConfig("bungee-guard", ""));
        assertFalse(missingBungeeGuardSecret.valid());
        assertTrue(missingBungeeGuardSecret.errors().stream().anyMatch(error -> error.contains("bungee-guard")));

        var unknown = validateWithForwarding(new ProxyConfig.ForwardingConfig("not-real", ""));
        assertFalse(unknown.valid());
        assertTrue(unknown.errors().stream().anyMatch(error -> error.contains("forwarding.mode")));
    }

    @Test
    void validatesStatusConfig() {
        var invalid = validateWithStatus(new ProxyConfig.StatusConfig(true, "motd", "proto", -2, -1));

        assertFalse(invalid.valid());
        assertTrue(invalid.errors().stream().anyMatch(error -> error.contains("status.protocolVersion")));
        assertTrue(invalid.errors().stream().anyMatch(error -> error.contains("status.maxPlayers")));

        var invalidRichStatus = validateWithStatus(new ProxyConfig.StatusConfig(
                true,
                "motd",
                "proto",
                763,
                100,
                "not-a-data-uri",
                List.of(new ProxyConfig.StatusSamplePlayer("", "not-a-uuid"))));

        assertFalse(invalidRichStatus.valid());
        assertTrue(invalidRichStatus.errors().stream().anyMatch(error -> error.contains("status.favicon")));
        assertTrue(invalidRichStatus.errors().stream().anyMatch(error -> error.contains("samplePlayers[0].name")));
        assertTrue(invalidRichStatus.errors().stream().anyMatch(error -> error.contains("samplePlayers[0].id")));
    }

    @Test
    void validatesAdminTlsFiles() throws Exception {
        var keyStore = tempDir.resolve("admin.p12");
        var trustStore = tempDir.resolve("clients.p12");
        Files.writeString(keyStore, "placeholder");
        Files.writeString(trustStore, "placeholder");

        var missingKeyStore = validateWithTls(new ProxyConfig.AdminTlsConfig(
                true,
                tempDir.resolve("missing.p12").toString(),
                "changeit",
                "PKCS12",
                "",
                "",
                "PKCS12",
                false));
        assertFalse(missingKeyStore.valid());
        assertTrue(missingKeyStore.errors().stream().anyMatch(error -> error.contains("keyStorePath does not exist")));

        var missingTrustStore = validateWithTls(new ProxyConfig.AdminTlsConfig(
                true,
                keyStore.toString(),
                "changeit",
                "PKCS12",
                "",
                "",
                "PKCS12",
                true));
        assertFalse(missingTrustStore.valid());
        assertTrue(missingTrustStore.errors().stream().anyMatch(error -> error.contains("trustStorePath must not be blank")));

        var trustStoreWithoutClientAuth = validateWithTls(new ProxyConfig.AdminTlsConfig(
                true,
                keyStore.toString(),
                "changeit",
                "PKCS12",
                trustStore.toString(),
                "changeit",
                "PKCS12",
                false));
        assertTrue(trustStoreWithoutClientAuth.valid());
        assertTrue(trustStoreWithoutClientAuth.warnings().stream().anyMatch(warning -> warning.contains("clientAuth is disabled")));

        var mtlsWithoutBearerToken = new ProxyConfig(
                new InetSocketAddress("127.0.0.1", 25577),
                0,
                true,
                ProxyConfig.NetworkConfig.defaults(),
                ProxyConfig.RegistryConfig.defaults(),
                ProxyConfig.CompressionConfig.defaults(),
                ProxyConfig.PacketAnalysisConfig.defaults(),
                ProxyConfig.ObservabilityConfig.defaults(),
                new ProxyConfig.AdminConfig(
                        true,
                        new InetSocketAddress("0.0.0.0", 8080),
                        "",
                        new ProxyConfig.AdminTlsConfig(
                                true,
                                keyStore.toString(),
                                "changeit",
                                "PKCS12",
                                trustStore.toString(),
                                "changeit",
                                "PKCS12",
                                true)));
        assertTrue(new ConfigValidator()
                .validate(new ConfigLoader.LoadedProxyConfig(mtlsWithoutBearerToken, List.of(server("one"))))
                .valid());
    }

    private static ServerDescriptor server(String name) {
        return new ServerDescriptor(
                name,
                new InetSocketAddress("127.0.0.1", 25565),
                Set.of("lobby"),
                Set.of(ServerCapability.MODERN_FORWARDING),
                new ProtocolRange(0, Integer.MAX_VALUE, "any"),
                100,
                100,
                120,
                false,
                Map.of());
    }

    private ConfigValidationResult validateWithTls(ProxyConfig.AdminTlsConfig tls) {
        var config = new ProxyConfig(
                new InetSocketAddress("127.0.0.1", 25577),
                0,
                true,
                ProxyConfig.NetworkConfig.defaults(),
                ProxyConfig.RegistryConfig.defaults(),
                ProxyConfig.CompressionConfig.defaults(),
                ProxyConfig.PacketAnalysisConfig.defaults(),
                ProxyConfig.ObservabilityConfig.defaults(),
                new ProxyConfig.AdminConfig(true, new InetSocketAddress("127.0.0.1", 8080), "secret", tls));
        return new ConfigValidator().validate(new ConfigLoader.LoadedProxyConfig(config, List.of(server("one"))));
    }

    private ConfigValidationResult validateWithForwarding(ProxyConfig.ForwardingConfig forwarding) {
        var config = new ProxyConfig(
                new InetSocketAddress("127.0.0.1", 25577),
                0,
                true,
                ProxyConfig.NetworkConfig.defaults(),
                ProxyConfig.RegistryConfig.defaults(),
                ProxyConfig.CompressionConfig.defaults(),
                ProxyConfig.PacketAnalysisConfig.defaults(),
                ProxyConfig.ObservabilityConfig.defaults(),
                new ProxyConfig.AdminConfig(true, new InetSocketAddress("127.0.0.1", 8080), "secret"),
                ProxyConfig.AuthConfig.defaults(),
                forwarding,
                ProxyConfig.NativeConfig.defaults());
        return new ConfigValidator().validate(new ConfigLoader.LoadedProxyConfig(config, List.of(server("one"))));
    }

    private ConfigValidationResult validateWithStatus(ProxyConfig.StatusConfig status) {
        var config = new ProxyConfig(
                new InetSocketAddress("127.0.0.1", 25577),
                0,
                true,
                ProxyConfig.NetworkConfig.defaults(),
                ProxyConfig.RegistryConfig.defaults(),
                ProxyConfig.CompressionConfig.defaults(),
                ProxyConfig.PacketAnalysisConfig.defaults(),
                ProxyConfig.ObservabilityConfig.defaults(),
                new ProxyConfig.AdminConfig(true, new InetSocketAddress("127.0.0.1", 8080), "secret"),
                status,
                ProxyConfig.AuthConfig.defaults(),
                ProxyConfig.ForwardingConfig.defaults(),
                ProxyConfig.NativeConfig.defaults());
        return new ConfigValidator().validate(new ConfigLoader.LoadedProxyConfig(config, List.of(server("one"))));
    }

    private static ServerDescriptor badCapacityServer() {
        return new ServerDescriptor(
                "bad-capacity",
                new InetSocketAddress("127.0.0.1", 25566),
                Set.of("lobby"),
                Set.of(ServerCapability.MODERN_FORWARDING),
                new ProtocolRange(0, Integer.MAX_VALUE, "any"),
                100,
                200,
                100,
                false,
                Map.of());
    }
}

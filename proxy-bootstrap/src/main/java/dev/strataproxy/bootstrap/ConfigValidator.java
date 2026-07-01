package dev.strataproxy.bootstrap;

import dev.strataproxy.api.server.ServerDescriptor;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.UUID;

/**
 * Performs semantic validation of loaded configuration before startup.
 */
public final class ConfigValidator {
    private static final int MIN_SAFE_MAX_FRAME_BYTES = 1_024;
    private static final java.util.Set<String> NATIVE_FEATURES = java.util.Set.of(
            "aes",
            "vaes",
            "pclmulqdq",
            "vpclmulqdq",
            "sha_ni",
            "crc32",
            "sse4_2",
            "avx2",
            "avx512f",
            "bmi1",
            "bmi2",
            "lzcnt",
            "popcnt",
            "neon",
            "arm_aes",
            "arm_sha");

    /**
     * Validates proxy settings and static server descriptors.
     *
     * @param loaded loaded configuration to validate
     * @return validation errors and warnings
     */
    public ConfigValidationResult validate(ConfigLoader.LoadedProxyConfig loaded) {
        var errors = new ArrayList<String>();
        var warnings = new ArrayList<String>();
        if (loaded == null) {
            errors.add("config is null");
            return new ConfigValidationResult(errors, warnings);
        }
        validateProxy(loaded.proxy(), errors, warnings);
        validateServers(loaded.proxy(), loaded.servers(), errors, warnings);
        return new ConfigValidationResult(errors, warnings);
    }

    private static void validateProxy(ProxyConfig config, ArrayList<String> errors, ArrayList<String> warnings) {
        if (config == null) {
            errors.add("proxy config is missing");
            return;
        }
        validateAddress("network.bind", config.bindAddress(), errors);
        if (config.workerThreads() < 0) {
            errors.add("network.workerThreads must be >= 0");
        }

        var network = config.network();
        if (network.maxFrameBytes() < MIN_SAFE_MAX_FRAME_BYTES) {
            errors.add("network.maxFrameBytes must be at least " + MIN_SAFE_MAX_FRAME_BYTES + " bytes");
        }
        if (network.connectTimeoutMillis() <= 0) {
            errors.add("network.connectTimeoutMillis must be positive");
        }
        if (network.writeBufferLowBytes() < 0 || network.writeBufferHighBytes() <= 0 || network.writeBufferLowBytes() >= network.writeBufferHighBytes()) {
            errors.add("network.writeBufferWatermark must satisfy 0 <= low < high");
        }
        if (network.maxConnections() <= 0) {
            errors.add("network.maxConnections must be positive");
        }
        if (network.maxConnectionsPerAddress() <= 0) {
            errors.add("network.maxConnectionsPerAddress must be positive");
        }
        if (network.maxConnectionsPerAddress() > network.maxConnections()) {
            errors.add("network.maxConnectionsPerAddress must be <= network.maxConnections");
        }
        if (network.maxNewConnectionsPerSecond() < 0) {
            errors.add("network.maxNewConnectionsPerSecond must be >= 0");
        }
        if (network.maxNewConnectionsPerAddressPerSecond() < 0) {
            errors.add("network.maxNewConnectionsPerAddressPerSecond must be >= 0");
        }
        if (network.initialHandshakeTimeoutMillis() <= 0) {
            errors.add("network.initialHandshakeTimeoutMillis must be positive");
        }

        var registry = config.registry();
        if (registry.persistenceEnabled() && registry.persistencePath().isBlank()) {
            errors.add("registry.persistencePath must not be blank when persistence is enabled");
        }
        validatePositive("registry.healthCheckInterval", registry.healthCheckInterval(), errors);
        validatePositive("registry.healthCheckTimeout", registry.healthCheckTimeout(), errors);
        var healthCheckMode = registry.healthCheckMode().trim().toLowerCase(Locale.ROOT);
        if (!healthCheckMode.equals("tcp") && !healthCheckMode.equals("minecraft-status")) {
            errors.add("registry.healthCheckMode must be one of: tcp, minecraft-status");
        }
        if (registry.healthCheckTimeout().compareTo(registry.healthCheckInterval()) > 0) {
            warnings.add("registry.healthCheckTimeout is greater than healthCheckInterval");
        }

        var compression = config.compression();
        var mode = compression.mode() == null ? "" : compression.mode().trim().toLowerCase(Locale.ROOT);
        if (!mode.equals("adaptive") && !mode.equals("off") && !mode.equals("fixed")) {
            errors.add("compression.mode must be one of: adaptive, fixed, off");
        }
        if (compression.minThreshold() < 0 || compression.maxThreshold() < compression.minThreshold()) {
            errors.add("compression thresholds must satisfy 0 <= minThreshold <= maxThreshold");
        }
        if (compression.cpuGuard() < 0.0d || compression.cpuGuard() > 1.0d) {
            errors.add("compression.cpuGuard must be between 0.0 and 1.0");
        }
        if (compression.rewriteMaxEventLoopDelayMillis() < 0) {
            errors.add("compression.rewriteMaxEventLoopDelayMillis must be >= 0");
        }
        var codec = compression.codec() == null ? "" : compression.codec().trim().toLowerCase(Locale.ROOT);
        if (!codec.equals("zlib") && !codec.equals("zstd")) {
            errors.add("compression.codec must be one of: zlib, zstd");
        }
        if (compression.zstdLevel() < -5 || compression.zstdLevel() > 22) {
            errors.add("compression.zstdLevel must be between -5 and 22");
        }
        if (!compression.zstdDictionaryPath().isBlank()) {
            var dictionaryPath = Path.of(compression.zstdDictionaryPath());
            if (Files.notExists(dictionaryPath)) {
                errors.add("compression.zstdDictionaryPath does not exist: " + compression.zstdDictionaryPath());
            }
        }
        if (codec.equals("zstd")) {
            warnings.add("compression.codec=zstd requires a modded client and matching backend/proxy negotiation");
        }

        var packetAnalysis = config.packetAnalysis();
        if (packetAnalysis.largePayloadWarnBytes() < 0) {
            errors.add("packetAnalysis.largePayloadWarnBytes must be >= 0");
        }
        if (packetAnalysis.unknownChannelThrottleBytes() < 0) {
            errors.add("packetAnalysis.unknownChannelThrottleBytes must be >= 0");
        }
        if (packetAnalysis.moddedHandshakeWarnBytes() < 0) {
            errors.add("packetAnalysis.moddedHandshakeWarnBytes must be >= 0");
        }
        if (packetAnalysis.customPayloadFloodMaxCount() < 0) {
            errors.add("packetAnalysis.customPayloadFloodMaxCount must be >= 0");
        }
        if (packetAnalysis.customPayloadFloodMaxCount() > 0) {
            validatePositive("packetAnalysis.customPayloadFloodWindow", packetAnalysis.customPayloadFloodWindow(), errors);
        }

        var observability = config.observability();
        if (observability.packetTopN() < 0) {
            errors.add("observability.packetTopN must be >= 0");
        }
        validatePositive("observability.flushInterval", observability.flushInterval(), errors);

        validateAuth(config.auth(), errors, warnings);
        validateStatus(config.status(), errors);
        validateForwarding(config.forwarding(), errors);
        validateNative(config.nativeRuntime(), errors, warnings);

        var admin = config.admin();
        if (admin.enabled()) {
            validateAddress("admin.bind", admin.bindAddress(), errors);
            if (admin.bearerToken().isBlank()) {
                if (admin.tls().clientAuth() || isLoopbackBind(admin.bindAddress())) {
                    warnings.add("admin.bearerToken is blank; admin endpoints except /healthz and /readyz are unauthenticated");
                } else {
                    errors.add("admin.bearerToken must not be blank when admin.bind is not loopback unless admin.tls.clientAuth is enabled");
                }
            }
            validateAdminTls(admin.tls(), errors, warnings);
        }
    }

    private static void validateAuth(ProxyConfig.AuthConfig auth, ArrayList<String> errors, ArrayList<String> warnings) {
        if (auth == null) {
            return;
        }
        if (auth.rsaKeyBits() < 1024) {
            errors.add("auth.rsaKeyBits must be at least 1024");
        }
        if (auth.verifyTokenBytes() <= 0 || auth.verifyTokenBytes() > 64) {
            errors.add("auth.verifyTokenBytes must be between 1 and 64");
        }
        if (auth.sessionVerification() && !auth.onlineMode()) {
            errors.add("auth.sessionVerification requires auth.onlineMode");
        }
        validatePositive("auth.sessionVerificationTimeout", auth.sessionVerificationTimeout(), errors);
        if (auth.onlineMode() && !auth.sessionVerification()) {
            warnings.add("auth.onlineMode is enabled without Mojang session verification; use only for staged integration tests");
        }
    }

    private static void validateStatus(ProxyConfig.StatusConfig status, ArrayList<String> errors) {
        if (status == null) {
            return;
        }
        if (status.protocolVersion() < -1) {
            errors.add("status.protocolVersion must be >= -1");
        }
        if (status.maxPlayers() < 0) {
            errors.add("status.maxPlayers must be >= 0");
        }
        if (!status.favicon().isBlank() && !status.favicon().startsWith("data:image/png;base64,")) {
            errors.add("status.favicon must be a data:image/png;base64 data URI");
        }
        if (status.favicon().length() > 128 * 1024) {
            errors.add("status.favicon must be <= 128kb after base64 encoding");
        }
        if (status.samplePlayers().size() > 12) {
            errors.add("status.samplePlayers must contain at most 12 entries");
        }
        for (var index = 0; index < status.samplePlayers().size(); index++) {
            var player = status.samplePlayers().get(index);
            if (player.name().isBlank()) {
                errors.add("status.samplePlayers[" + index + "].name must not be blank");
            }
            if (player.name().length() > 64) {
                errors.add("status.samplePlayers[" + index + "].name must be <= 64 characters");
            }
            try {
                UUID.fromString(player.id());
            } catch (RuntimeException exception) {
                errors.add("status.samplePlayers[" + index + "].id must be a UUID");
            }
        }
    }

    private static void validateForwarding(ProxyConfig.ForwardingConfig forwarding, ArrayList<String> errors) {
        if (forwarding == null) {
            return;
        }
        var mode = forwarding.mode() == null ? "" : forwarding.mode().trim().toLowerCase(Locale.ROOT);
        if (!mode.equals("none")
                && !mode.equals("velocity-modern")
                && !mode.equals("bungee-legacy")
                && !mode.equals("bungee-guard")) {
            errors.add("forwarding.mode must be one of: none, velocity-modern, bungee-legacy, bungee-guard");
        }
        if (mode.equals("velocity-modern") && forwarding.secret().isBlank()) {
            errors.add("forwarding.secret must not be blank when forwarding.mode is velocity-modern");
        }
        if (mode.equals("bungee-guard") && forwarding.secret().isBlank()) {
            errors.add("forwarding.secret must not be blank when forwarding.mode is bungee-guard");
        }
    }

    private static void validateNative(ProxyConfig.NativeConfig nativeConfig, ArrayList<String> errors, ArrayList<String> warnings) {
        if (nativeConfig == null) {
            return;
        }
        validateFeatureSet("native.disabledFeatures", nativeConfig.disabledFeatures(), errors);
        validateFeatureSet("native.forcedFeatures", nativeConfig.forcedFeatures(), errors);
        var overlap = new java.util.HashSet<String>();
        for (var feature : nativeConfig.disabledFeatures()) {
            overlap.add(normalizedFeature(feature));
        }
        for (var feature : nativeConfig.forcedFeatures()) {
            if (overlap.contains(normalizedFeature(feature))) {
                errors.add("native feature cannot be both disabled and forced: " + feature);
            }
        }
        if (!nativeConfig.enabled() && nativeConfig.requireNativeTransport()) {
            errors.add("native.requireNativeTransport cannot be true when native.enabled is false");
        }
        if (!nativeConfig.autoDetect() && nativeConfig.forcedFeatures().isEmpty()) {
            warnings.add("native.autoDetect is false and no native.forcedFeatures are configured");
        }
    }

    private static void validateFeatureSet(String field, java.util.Set<String> features, ArrayList<String> errors) {
        if (features == null) {
            return;
        }
        for (var feature : features) {
            if (!NATIVE_FEATURES.contains(normalizedFeature(feature))) {
                errors.add(field + " contains unknown feature: " + feature);
            }
        }
    }

    private static String normalizedFeature(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static void validateAdminTls(ProxyConfig.AdminTlsConfig tls, ArrayList<String> errors, ArrayList<String> warnings) {
        if (tls == null || !tls.enabled()) {
            return;
        }
        if (tls.keyStorePath().isBlank()) {
            errors.add("admin.tls.keyStorePath must not be blank when admin TLS is enabled");
        } else if (Files.notExists(Path.of(tls.keyStorePath()))) {
            errors.add("admin.tls.keyStorePath does not exist: " + tls.keyStorePath());
        }
        if (tls.keyStoreType().isBlank()) {
            errors.add("admin.tls.keyStoreType must not be blank when admin TLS is enabled");
        }
        if (tls.clientAuth()) {
            if (tls.trustStorePath().isBlank()) {
                errors.add("admin.tls.trustStorePath must not be blank when admin TLS clientAuth is enabled");
            } else if (Files.notExists(Path.of(tls.trustStorePath()))) {
                errors.add("admin.tls.trustStorePath does not exist: " + tls.trustStorePath());
            }
            if (tls.trustStoreType().isBlank()) {
                errors.add("admin.tls.trustStoreType must not be blank when admin TLS clientAuth is enabled");
            }
        } else if (!tls.trustStorePath().isBlank()) {
            warnings.add("admin.tls.trustStorePath is configured but clientAuth is disabled");
        }
    }

    private static void validateServers(ProxyConfig config, Iterable<ServerDescriptor> servers, ArrayList<String> errors, ArrayList<String> warnings) {
        var names = new HashSet<String>();
        var count = 0;
        for (var server : servers) {
            count++;
            if (!names.add(server.name())) {
                errors.add("duplicate server name: " + server.name());
            }
            validateAddress("server." + server.name() + ".address", server.address(), errors);
            if (server.softCapacity() < 0 || server.hardCapacity() < 0) {
                errors.add("server." + server.name() + " capacity values must be non-negative");
            }
            if (server.hardCapacity() > 0 && server.softCapacity() > server.hardCapacity()) {
                errors.add("server." + server.name() + " softCapacity must be <= hardCapacity");
            }
            if (server.drainMode()) {
                warnings.add("server." + server.name() + " starts in drain mode");
            }
        }
        if (count == 0) {
            if (config.admin().enabled()) {
                warnings.add("no static servers configured; use Admin API or persisted registry entries to add backends");
                if (!config.registry().persistenceEnabled()) {
                    warnings.add("registry persistence is disabled; dynamically registered servers will not survive restart");
                }
            } else {
                errors.add("at least one server must be configured when admin API is disabled");
            }
        }
    }

    private static void validateAddress(String field, InetSocketAddress address, ArrayList<String> errors) {
        if (address == null) {
            errors.add(field + " is missing");
            return;
        }
        if (address.getHostString() == null || address.getHostString().isBlank()) {
            errors.add(field + " host must not be blank");
        }
        if (address.getPort() < 0 || address.getPort() > 65535) {
            errors.add(field + " port must be between 0 and 65535");
        }
    }

    private static boolean isLoopbackBind(InetSocketAddress address) {
        if (address == null) {
            return false;
        }
        var resolved = address.getAddress();
        if (resolved != null) {
            return resolved.isLoopbackAddress();
        }
        var host = address.getHostString();
        return host.equalsIgnoreCase("localhost")
                || host.equals("127.0.0.1")
                || host.equals("::1")
                || host.equals("[::1]");
    }

    private static void validatePositive(String field, Duration duration, ArrayList<String> errors) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            errors.add(field + " must be positive");
        }
    }
}

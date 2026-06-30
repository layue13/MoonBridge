package dev.strataproxy.bootstrap;

import dev.strataproxy.api.server.ServerDescriptor;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;

public final class ConfigValidator {
    private static final int MIN_SAFE_MAX_FRAME_BYTES = 1_024;

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
        if (network.initialHandshakeTimeoutMillis() <= 0) {
            errors.add("network.initialHandshakeTimeoutMillis must be positive");
        }

        var registry = config.registry();
        if (registry.persistenceEnabled() && registry.persistencePath().isBlank()) {
            errors.add("registry.persistencePath must not be blank when persistence is enabled");
        }
        validatePositive("registry.healthCheckInterval", registry.healthCheckInterval(), errors);
        validatePositive("registry.healthCheckTimeout", registry.healthCheckTimeout(), errors);
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

package dev.strataproxy.bootstrap;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.strataproxy.api.server.ProtocolRange;
import dev.strataproxy.api.server.ServerCapability;
import dev.strataproxy.api.server.ServerDescriptor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class ConfigLoader {
    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public LoadedProxyConfig load(Path path) throws IOException {
        return load(path, System.getenv());
    }

    public LoadedProxyConfig load(Path path, Map<String, String> environment) throws IOException {
        if (Files.notExists(path)) {
            return LoadedProxyConfig.defaults();
        }
        var yaml = expandEnvironment(Files.readString(path), environment);
        var file = mapper.readValue(yaml, ConfigFile.class);
        return file.toLoadedConfig();
    }

    public record LoadedProxyConfig(ProxyConfig proxy, List<ServerDescriptor> servers) {
        public static LoadedProxyConfig defaults() {
            return new LoadedProxyConfig(ProxyConfig.defaults(), List.of(defaultServer()));
        }

        private static ServerDescriptor defaultServer() {
            return new ServerDescriptor(
                    "lobby-1",
                    new InetSocketAddress("127.0.0.1", 25565),
                    Set.of("lobby"),
                    Set.of(ServerCapability.MODERN_FORWARDING),
                    new ProtocolRange(0, Integer.MAX_VALUE, "any"),
                    100,
                    500,
                    600,
                    false,
                    Map.of("group", "lobby", "host", "localhost"));
        }
    }

    public static final class ConfigFile {
        public NetworkFile network = new NetworkFile();
        public RegistryFile registry = new RegistryFile();
        public CompressionFile compression = new CompressionFile();
        public PacketAnalysisFile packetAnalysis = new PacketAnalysisFile();
        public ObservabilityFile observability = new ObservabilityFile();
        public AdminFile admin = new AdminFile();
        public AuthFile auth = new AuthFile();
        @JsonProperty("native")
        public NativeFile nativeRuntime = new NativeFile();
        public List<ServerFile> servers = List.of();

        LoadedProxyConfig toLoadedConfig() {
            var nativeFile = nativeRuntime == null ? new NativeFile() : nativeRuntime;
            var bind = parseAddress(network.bind, 25577);
            var proxy = new ProxyConfig(
                    bind,
                    network.workerThreads,
                    network.nativeTransport,
                    new ProxyConfig.NetworkConfig(
                            parseBytes(network.maxFrameBytes, 8 * 1024 * 1024),
                            network.connectTimeoutMillis,
                            parseBytes(network.writeBufferLow, 4 * 1024 * 1024),
                            parseBytes(network.writeBufferHigh, 16 * 1024 * 1024),
                            network.maxConnections,
                            network.maxConnectionsPerAddress,
                            network.initialHandshakeTimeoutMillis),
                    new ProxyConfig.RegistryConfig(
                            registry.staticServers,
                            registry.persistenceEnabled,
                            registry.persistencePath,
                            registry.healthCheckEnabled,
                            java.time.Duration.ofMillis(parseDurationMillis(registry.healthCheckInterval, 5_000)),
                            java.time.Duration.ofMillis(parseDurationMillis(registry.healthCheckTimeout, 2_000))),
                    new ProxyConfig.CompressionConfig(
                            compression.mode,
                            compression.minThreshold,
                            compression.maxThreshold,
                            compression.cpuGuard,
                            compression.rewriteEnabled,
                            compression.rewriteMaxEventLoopDelayMillis),
                    new ProxyConfig.PacketAnalysisConfig(
                            parseBytes(packetAnalysis.largePayloadWarnBytes, 1024 * 1024),
                            parseBytes(packetAnalysis.unknownChannelThrottleBytes, 256 * 1024),
                            parseBytes(packetAnalysis.moddedHandshakeWarnBytes, 2 * 1024 * 1024),
                            packetAnalysis.customPayloadFloodMaxCount,
                            java.time.Duration.ofMillis(parseDurationMillis(packetAnalysis.customPayloadFloodWindow, 10_000))),
                    new ProxyConfig.ObservabilityConfig(observability.prometheus, observability.packetTopN, observability.anomalySampling, java.time.Duration.ofSeconds(observability.flushIntervalSeconds)),
                    new ProxyConfig.AdminConfig(
                            admin.enabled,
                            parseAddress(admin.bind, 8080),
                            admin.bearerToken,
                            new ProxyConfig.AdminTlsConfig(
                                    admin.tls.enabled,
                                    admin.tls.keyStorePath,
                                    admin.tls.keyStorePassword,
                                    admin.tls.keyStoreType,
                                    admin.tls.trustStorePath,
                                    admin.tls.trustStorePassword,
                                    admin.tls.trustStoreType,
                                    admin.tls.clientAuth)),
                    new ProxyConfig.AuthConfig(
                            auth.onlineMode,
                            auth.rsaKeyBits,
                            auth.verifyTokenBytes,
                            auth.sessionVerification,
                            java.time.Duration.ofMillis(parseDurationMillis(auth.sessionVerificationTimeout, 5_000))),
                    new ProxyConfig.NativeConfig(
                            nativeFile.enabled,
                            nativeFile.autoDetect,
                            nativeFile.preferNativeTransport,
                            nativeFile.requireNativeTransport,
                            nativeFile.preferOpenSslTls,
                            nativeFile.preferNativeCompression,
                            nativeFile.disabledFeatures,
                            nativeFile.forcedFeatures));
            var descriptors = registry.staticServers
                    ? servers.stream().map(ServerFile::toDescriptor).toList()
                    : List.<ServerDescriptor>of();
            return new LoadedProxyConfig(proxy, descriptors);
        }
    }

    public static final class NetworkFile {
        public String bind = "0.0.0.0:25577";
        public int workerThreads = 0;
        public boolean nativeTransport = true;
        public String maxFrameBytes = "8mb";
        public int connectTimeoutMillis = 5_000;
        public String writeBufferLow = "4mb";
        public String writeBufferHigh = "16mb";
        public int maxConnections = 10_000;
        public int maxConnectionsPerAddress = 200;
        public int initialHandshakeTimeoutMillis = 5_000;
    }

    public static final class RegistryFile {
        public boolean staticServers = true;
        public boolean persistenceEnabled = true;
        public String persistencePath = "data/registry.json";
        public boolean healthCheckEnabled = true;
        public String healthCheckInterval = "5s";
        public String healthCheckTimeout = "2s";
    }

    public static final class CompressionFile {
        public String mode = "adaptive";
        public int minThreshold = 256;
        public int maxThreshold = 8192;
        public double cpuGuard = 0.75d;
        public boolean rewriteEnabled = false;
        public int rewriteMaxEventLoopDelayMillis = 25;
    }

    public static final class PacketAnalysisFile {
        public String largePayloadWarnBytes = "1mb";
        public String unknownChannelThrottleBytes = "256kb";
        public String moddedHandshakeWarnBytes = "2mb";
        public int customPayloadFloodMaxCount = 200;
        public String customPayloadFloodWindow = "10s";
    }

    public static final class ObservabilityFile {
        public boolean prometheus = true;
        public int packetTopN = 50;
        public boolean anomalySampling = true;
        public long flushIntervalSeconds = 5;
    }

    public static final class NativeFile {
        public boolean enabled = true;
        public boolean autoDetect = true;
        public boolean preferNativeTransport = true;
        public boolean requireNativeTransport = false;
        public boolean preferOpenSslTls = false;
        public boolean preferNativeCompression = false;
        public Set<String> disabledFeatures = Set.of();
        public Set<String> forcedFeatures = Set.of();
    }

    public static final class AuthFile {
        public boolean onlineMode = false;
        public int rsaKeyBits = 1024;
        public int verifyTokenBytes = 4;
        public boolean sessionVerification = false;
        public String sessionVerificationTimeout = "5s";
    }

    public static final class AdminFile {
        public boolean enabled = true;
        public String bind = "127.0.0.1:8080";
        public String bearerToken = "";
        public AdminTlsFile tls = new AdminTlsFile();
    }

    public static final class AdminTlsFile {
        public boolean enabled = false;
        public String keyStorePath = "";
        public String keyStorePassword = "";
        public String keyStoreType = "PKCS12";
        public String trustStorePath = "";
        public String trustStorePassword = "";
        public String trustStoreType = "PKCS12";
        public boolean clientAuth = false;
    }

    public static final class ServerFile {
        public String name;
        public String address;
        public Set<String> tags = Set.of();
        public Set<String> capabilities = Set.of();
        public String protocolRange = "any";
        public int weight = 100;
        public int softCapacity = 500;
        public int hardCapacity = 600;
        public boolean drainMode = false;
        public Map<String, String> metadata = Map.of();

        ServerDescriptor toDescriptor() {
            return new ServerDescriptor(
                    name,
                    parseAddress(address, 25565),
                    tags,
                    capabilities.stream().map(ServerFile::parseCapability).collect(Collectors.toUnmodifiableSet()),
                    parseProtocolRange(protocolRange),
                    weight,
                    softCapacity,
                    hardCapacity,
                    drainMode,
                    metadata);
        }

        private static ServerCapability parseCapability(String value) {
            return ServerCapability.valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
        }
    }

    static InetSocketAddress parseAddress(String value, int defaultPort) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("address must not be blank");
        }
        var trimmed = value.trim();
        var splitAt = trimmed.lastIndexOf(':');
        if (splitAt <= 0) {
            return new InetSocketAddress(trimmed, defaultPort);
        }
        return new InetSocketAddress(trimmed.substring(0, splitAt), Integer.parseInt(trimmed.substring(splitAt + 1)));
    }

    static ProtocolRange parseProtocolRange(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("any")) {
            return new ProtocolRange(0, Integer.MAX_VALUE, "any");
        }
        var trimmed = value.trim();
        if (trimmed.matches("\\d+")) {
            var protocol = Integer.parseInt(trimmed);
            return new ProtocolRange(protocol, protocol, trimmed);
        }
        var bounds = trimmed.split("\\.\\.", 2);
        if (bounds.length == 2) {
            return new ProtocolRange(Integer.parseInt(bounds[0]), Integer.parseInt(bounds[1]), trimmed);
        }
        throw new IllegalArgumentException("unsupported protocolRange: " + value);
    }

    static int parseBytes(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        var trimmed = value.trim().toLowerCase(Locale.ROOT);
        var multiplier = switch (trimmed.replaceAll("[0-9_ ]", "")) {
            case "kb", "k" -> 1024;
            case "mb", "m" -> 1024 * 1024;
            case "b", "" -> 1;
            default -> throw new IllegalArgumentException("unsupported byte unit: " + value);
        };
        var number = trimmed.replaceAll("[^0-9]", "");
        return Math.toIntExact(Long.parseLong(number) * multiplier);
    }

    static long parseDurationMillis(String value, long defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        var trimmed = value.trim().toLowerCase(Locale.ROOT);
        var unit = trimmed.replaceAll("[0-9_ .]", "");
        var number = trimmed.replaceAll("[^0-9.]", "");
        var parsed = Double.parseDouble(number);
        return switch (unit) {
            case "ms", "" -> Math.round(parsed);
            case "s" -> Math.round(parsed * 1_000);
            case "m" -> Math.round(parsed * 60_000);
            default -> throw new IllegalArgumentException("unsupported duration unit: " + value);
        };
    }

    static String expandEnvironment(String value, Map<String, String> environment) {
        if (value == null || value.indexOf('$') < 0) {
            return value;
        }
        var expanded = new StringBuilder(value.length());
        var index = 0;
        while (index < value.length()) {
            var current = value.charAt(index);
            if (current != '$' || index + 1 >= value.length() || value.charAt(index + 1) != '{') {
                expanded.append(current);
                index++;
                continue;
            }
            var end = value.indexOf('}', index + 2);
            if (end < 0) {
                expanded.append(current);
                index++;
                continue;
            }
            var expression = value.substring(index + 2, end);
            var defaultSeparator = expression.indexOf(':');
            var name = defaultSeparator < 0 ? expression : expression.substring(0, defaultSeparator);
            var defaultValue = defaultSeparator < 0 ? "" : expression.substring(defaultSeparator + 1);
            if (!isEnvironmentName(name)) {
                expanded.append(value, index, end + 1);
            } else {
                expanded.append(environment.getOrDefault(name, defaultValue));
            }
            index = end + 1;
        }
        return expanded.toString();
    }

    private static boolean isEnvironmentName(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (var i = 0; i < value.length(); i++) {
            var current = value.charAt(i);
            var valid = current == '_' || Character.isDigit(current) && i > 0 || current >= 'A' && current <= 'Z';
            if (!valid) {
                return false;
            }
        }
        return true;
    }
}

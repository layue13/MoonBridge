package dev.strataproxy.app;

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
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Loads YAML configuration into validated runtime configuration records.
 */
public final class ConfigLoader {
    /**
     * Creates ConfigLoader.
     */
    public ConfigLoader() {
    }

    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * Loads configuration from a path and expands variables from the process environment.
     *
     * @param path YAML config path
     * @return loaded proxy and static server configuration, or defaults when the file does not exist
     * @throws IOException when the file cannot be read or parsed
     */
    public LoadedProxyConfig load(Path path) throws IOException {
        return load(path, System.getenv());
    }

    /**
     * Loads configuration from a path using an explicit environment map for variable expansion.
     *
     * @param path YAML config path
     * @param environment environment variables used for {@code ${NAME:default}} expansion
     * @return loaded proxy and static server configuration, or defaults when the file does not exist
     * @throws IOException when the file cannot be read or parsed
     */
    public LoadedProxyConfig load(Path path, Map<String, String> environment) throws IOException {
        if (Files.notExists(path)) {
            return LoadedProxyConfig.defaults();
        }
        var yaml = expandEnvironment(Files.readString(path), environment);
        var file = mapper.readValue(yaml, ConfigFile.class);
        return file.toLoadedConfig(path.toAbsolutePath().getParent());
    }

    /**
     * Fully materialized configuration loaded from disk.
     *
     * @param proxy proxy runtime configuration
     * @param servers static backend descriptors loaded from the file
     */
    public record LoadedProxyConfig(ProxyConfig proxy, List<ServerDescriptor> servers) {
        /**
 * Documents this public API element.
 *
         * @return default proxy configuration with one local lobby backend
         */
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

    /**
     * Jackson-bound representation of the top-level YAML file.
     */
    public static final class ConfigFile {
        /**
         * Creates ConfigFile.
         */
        public ConfigFile() {
        }

        /** YAML value for network. */
        /** Public field for network. */
        public NetworkFile network = new NetworkFile();
        /** YAML value for registry. */
        /** Public field for registry. */
        public RegistryFile registry = new RegistryFile();
        /** YAML value for compression. */
        /** Public field for compression. */
        public CompressionFile compression = new CompressionFile();
        /** YAML value for observability. */
        /** Public field for observability. */
        public ObservabilityFile observability = new ObservabilityFile();
        /** YAML value for status. */
        /** Public field for status. */
        public StatusFile status = new StatusFile();
        /** YAML value for auth. */
        /** Public field for auth. */
        public AuthFile auth = new AuthFile();
        /** YAML value for forwarding. */
        /** Public field for forwarding. */
        public ForwardingFile forwarding = new ForwardingFile();
        /** YAML value for native runtime. */
        /** Public field for native runtime. */
        @JsonProperty("native")
        public NativeFile nativeRuntime = new NativeFile();
        /** YAML value for the authenticated backend-agent endpoint. */
        public BackendAgentFile backendAgent = new BackendAgentFile();
        /** YAML value for servers. */
        /** Public field for servers. */
        public List<ServerFile> servers = List.of();

        LoadedProxyConfig toLoadedConfig(Path configDirectory) {
            var nativeFile = nativeRuntime == null ? new NativeFile() : nativeRuntime;
            var statusFile = status == null ? new StatusFile() : status;
            var backendAgentFile = backendAgent == null ? new BackendAgentFile() : backendAgent;
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
                            network.maxNewConnectionsPerSecond,
                            network.maxNewConnectionsPerAddressPerSecond,
                            network.initialHandshakeTimeoutMillis,
                            network.proxyProtocol),
                    new ProxyConfig.RegistryConfig(
                            registry.staticServers,
                            registry.persistenceEnabled,
                            registry.persistencePath,
                            registry.healthCheckEnabled,
                            java.time.Duration.ofMillis(parseDurationMillis(registry.healthCheckInterval, 5_000)),
                            java.time.Duration.ofMillis(parseDurationMillis(registry.healthCheckTimeout, 2_000)),
                            registry.healthCheckMode),
                    new ProxyConfig.CompressionConfig(
                            compression.mode,
                            compression.minThreshold,
                            compression.maxThreshold,
                            compression.cpuGuard,
                            compression.rewriteEnabled,
                            compression.rewriteMaxEventLoopDelayMillis),
                    new ProxyConfig.ObservabilityConfig(java.time.Duration.ofSeconds(observability.flushIntervalSeconds)),
                    new ProxyConfig.StatusConfig(
                            statusFile.enabled,
                            statusFile.motd,
                            statusFile.protocolName,
                            statusFile.protocolVersion,
                            statusFile.maxPlayers,
                            resolveStatusFavicon(statusFile, configDirectory),
                            statusSamplePlayers(statusFile).stream()
                                    .map(player -> new ProxyConfig.StatusSamplePlayer(player.name, player.id))
                                    .toList()),
                    new ProxyConfig.AuthConfig(
                            auth.onlineMode,
                            auth.rsaKeyBits,
                            auth.verifyTokenBytes,
                            auth.sessionVerification,
                            java.time.Duration.ofMillis(parseDurationMillis(auth.sessionVerificationTimeout, 5_000))),
                    new ProxyConfig.ForwardingConfig(
                            forwarding.mode,
                            forwarding.secret),
                    new ProxyConfig.NativeConfig(
                            nativeFile.enabled,
                            nativeFile.autoDetect,
                            nativeFile.preferNativeTransport,
                            nativeFile.requireNativeTransport,
                            nativeFile.preferOpenSslTls,
                            nativeFile.preferNativeCompression,
                            nativeFile.disabledFeatures,
                            nativeFile.forcedFeatures),
                    new ProxyConfig.BackendAgentConfig(
                            backendAgentFile.enabled,
                            parseAddress(backendAgentFile.bind, 25578),
                            backendAgentFile.sharedSecret,
                            java.time.Duration.ofMillis(parseDurationMillis(backendAgentFile.heartbeatTimeout, 30_000)),
                            backendAgentFile.agentSecrets,
                            backendAgentFile.maxConnections,
                            backendAgentFile.maxQueuedConnections,
                            backendAgentFile.maxNonces));
            var descriptors = registry.staticServers
                    ? servers.stream().map(ServerFile::toDescriptor).toList()
                    : List.<ServerDescriptor>of();
            return new LoadedProxyConfig(proxy, descriptors);
        }

        private static List<StatusSamplePlayerFile> statusSamplePlayers(StatusFile status) {
            return status.samplePlayers == null ? List.of() : status.samplePlayers;
        }

        private static String resolveStatusFavicon(StatusFile status, Path configDirectory) {
            if (status.favicon != null && !status.favicon.isBlank()) {
                return status.favicon;
            }
            if (status.faviconPath == null || status.faviconPath.isBlank()) {
                return "";
            }
            var path = Path.of(status.faviconPath);
            if (!path.isAbsolute() && configDirectory != null) {
                path = configDirectory.resolve(path);
            }
            try {
                return "data:image/png;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(path));
            } catch (IOException exception) {
                throw new IllegalArgumentException("failed to read status.faviconPath: " + path, exception);
            }
        }
    }

    /**
     * YAML configuration for the narrow backend-agent registration endpoint.
     */
    public static final class BackendAgentFile {
        /** Enables the endpoint. */
        public boolean enabled = false;
        /** Bind address; keep loopback unless backend agents are on another protected host. */
        public String bind = "127.0.0.1:25578";
        /** Legacy shared secret. It is rejected when the endpoint is enabled. */
        public String sharedSecret = "";
        /** Per-agent secrets keyed by agent/backend ID. */
        public Map<String, String> agentSecrets = Map.of();
        /** Maximum interval without a heartbeat before an agent-owned backend is removed. */
        public String heartbeatTimeout = "30s";
        /** Maximum active agent request workers. */
        public int maxConnections = 32;
        /** Maximum accepted requests waiting for an agent worker. */
        public int maxQueuedConnections = 64;
        /** Maximum authenticated replay nonces retained across all agents. */
        public int maxNonces = 4096;
    }

    /**
     * YAML network section.
     */
    public static final class NetworkFile {
        /**
         * Creates NetworkFile.
         */
        public NetworkFile() {
        }

        /** YAML value for bind. */
        /** Public field for bind. */
        public String bind = "0.0.0.0:25577";
        /** YAML value for worker threads. */
        /** Public field for worker threads. */
        public int workerThreads = 0;
        /** YAML value for native transport. */
        /** Public field for native transport. */
        public boolean nativeTransport = true;
        /** YAML value for max frame bytes. */
        /** Public field for max frame bytes. */
        public String maxFrameBytes = "8mb";
        /** YAML value for connect timeout millis. */
        /** Public field for connect timeout millis. */
        public int connectTimeoutMillis = 5_000;
        /** YAML value for write buffer low. */
        /** Public field for write buffer low. */
        public String writeBufferLow = "4mb";
        /** YAML value for write buffer high. */
        /** Public field for write buffer high. */
        public String writeBufferHigh = "16mb";
        /** YAML value for max connections. */
        /** Public field for max connections. */
        public int maxConnections = 10_000;
        /** YAML value for max connections per address. */
        /** Public field for max connections per address. */
        public int maxConnectionsPerAddress = 200;
        /** YAML value for max new connections per second. */
        /** Public field for max new connections per second. */
        public int maxNewConnectionsPerSecond = 0;
        /** YAML value for max new connections per address per second. */
        /** Public field for max new connections per address per second. */
        public int maxNewConnectionsPerAddressPerSecond = 0;
        /** YAML value for initial handshake timeout millis. */
        /** Public field for initial handshake timeout millis. */
        public int initialHandshakeTimeoutMillis = 5_000;
        /** YAML value for proxy protocol. */
        /** Public field for proxy protocol. */
        public boolean proxyProtocol = false;
    }

    /**
     * YAML registry section.
     */
    public static final class RegistryFile {
        /**
         * Creates RegistryFile.
         */
        public RegistryFile() {
        }

        /** YAML value for static servers. */
        /** Public field for static servers. */
        public boolean staticServers = true;
        /** YAML value for persistence enabled. */
        /** Public field for persistence enabled. */
        public boolean persistenceEnabled = true;
        /** YAML value for persistence path. */
        /** Public field for persistence path. */
        public String persistencePath = "data/registry.json";
        /** YAML value for health check enabled. */
        /** Public field for health check enabled. */
        public boolean healthCheckEnabled = true;
        /** YAML value for health check interval. */
        /** Public field for health check interval. */
        public String healthCheckInterval = "5s";
        /** YAML value for health check timeout. */
        /** Public field for health check timeout. */
        public String healthCheckTimeout = "2s";
        /** YAML value for health check mode. */
        /** Public field for health check mode. */
        public String healthCheckMode = "tcp";
    }

    /**
     * YAML compression section.
     */
    public static final class CompressionFile {
        /**
         * Creates CompressionFile.
         */
        public CompressionFile() {
        }

        /** YAML value for mode. */
        /** Public field for mode. */
        public String mode = "adaptive";
        /** YAML value for min threshold. */
        /** Public field for min threshold. */
        public int minThreshold = 256;
        /** YAML value for max threshold. */
        /** Public field for max threshold. */
        public int maxThreshold = 8192;
        /** YAML value for cpu guard. */
        /** Public field for cpu guard. */
        public double cpuGuard = 0.75d;
        /** YAML value for rewrite enabled. */
        /** Public field for rewrite enabled. */
        public boolean rewriteEnabled = false;
        /** YAML value for rewrite max event loop delay millis. */
        /** Public field for rewrite max event loop delay millis. */
        public int rewriteMaxEventLoopDelayMillis = 25;
    }

    /**
     * YAML observability section.
     */
    public static final class ObservabilityFile {
        /**
         * Creates ObservabilityFile.
         */
        public ObservabilityFile() {
        }

        /** YAML value for flush interval seconds. */
        /** Public field for flush interval seconds. */
        public long flushIntervalSeconds = 5;
    }

    /**
     * YAML native-runtime section.
     */
    public static final class NativeFile {
        /**
         * Creates NativeFile.
         */
        public NativeFile() {
        }

        /** YAML value for enabled. */
        /** Public field for enabled. */
        public boolean enabled = true;
        /** YAML value for auto detect. */
        /** Public field for auto detect. */
        public boolean autoDetect = true;
        /** YAML value for prefer native transport. */
        /** Public field for prefer native transport. */
        public boolean preferNativeTransport = true;
        /** YAML value for require native transport. */
        /** Public field for require native transport. */
        public boolean requireNativeTransport = false;
        /** YAML value for prefer open ssl tls. */
        /** Public field for prefer open ssl tls. */
        public boolean preferOpenSslTls = false;
        /** YAML value for prefer native compression. */
        /** Public field for prefer native compression. */
        public boolean preferNativeCompression = false;
        /** YAML value for disabled features. */
        /** Public field for disabled features. */
        public Set<String> disabledFeatures = Set.of();
        /** YAML value for forced features. */
        /** Public field for forced features. */
        public Set<String> forcedFeatures = Set.of();
    }

    /**
     * YAML authentication section.
     */
    public static final class AuthFile {
        /**
         * Creates AuthFile.
         */
        public AuthFile() {
        }

        /** YAML value for online mode. */
        /** Public field for online mode. */
        public boolean onlineMode = false;
        /** YAML value for rsa key bits. */
        /** Public field for rsa key bits. */
        public int rsaKeyBits = 1024;
        /** YAML value for verify token bytes. */
        /** Public field for verify token bytes. */
        public int verifyTokenBytes = 4;
        /** YAML value for session verification. */
        /** Public field for session verification. */
        public boolean sessionVerification = false;
        /** YAML value for session verification timeout. */
        /** Public field for session verification timeout. */
        public String sessionVerificationTimeout = "5s";
    }

    /**
     * YAML status-ping section.
     */
    public static final class StatusFile {
        /**
         * Creates StatusFile.
         */
        public StatusFile() {
        }

        /** YAML value for enabled. */
        /** Public field for enabled. */
        public boolean enabled = true;
        /** YAML value for motd. */
        /** Public field for motd. */
        public String motd = "StrataProxy";
        /** YAML value for protocol name. */
        /** Public field for protocol name. */
        public String protocolName = "StrataProxy";
        /** YAML value for protocol version. */
        /** Public field for protocol version. */
        public int protocolVersion = -1;
        /** YAML value for max players. */
        /** Public field for max players. */
        public int maxPlayers = 1000;
        /** YAML value for favicon. */
        /** Public field for favicon. */
        public String favicon = "";
        /** YAML value for favicon path. */
        /** Public field for favicon path. */
        public String faviconPath = "";
        /** YAML value for sample players. */
        /** Public field for sample players. */
        public List<StatusSamplePlayerFile> samplePlayers = List.of();
    }

    /**
     * YAML sample player entry for status responses.
     */
    public static final class StatusSamplePlayerFile {
        /**
         * Creates StatusSamplePlayerFile.
         */
        public StatusSamplePlayerFile() {
        }

        /** YAML value for name. */
        /** Public field for name. */
        public String name = "";
        /** YAML value for id. */
        /** Public field for id. */
        public String id = "00000000-0000-0000-0000-000000000000";
    }

    /**
     * YAML player-forwarding section.
     */
    public static final class ForwardingFile {
        /**
         * Creates ForwardingFile.
         */
        public ForwardingFile() {
        }

        /** YAML value for mode. */
        /** Public field for mode. */
        public String mode = "none";
        /** YAML value for secret. */
        /** Public field for secret. */
        public String secret = "";
    }

    /**
     * YAML backend server entry.
     */
    public static final class ServerFile {
        /**
         * Creates ServerFile.
         */
        public ServerFile() {
        }

        /** YAML value for name. */
        /** Public field for name. */
        public String name;
        /** YAML value for address. */
        /** Public field for address. */
        public String address;
        /** YAML value for tags. */
        /** Public field for tags. */
        public Set<String> tags = Set.of();
        /** YAML value for capabilities. */
        /** Public field for capabilities. */
        public Set<String> capabilities = Set.of();
        /** YAML value for protocol range. */
        /** Public field for protocol range. */
        public String protocolRange = "any";
        /** YAML value for weight. */
        /** Public field for weight. */
        public int weight = 100;
        /** YAML value for soft capacity. */
        /** Public field for soft capacity. */
        public int softCapacity = 500;
        /** YAML value for hard capacity. */
        /** Public field for hard capacity. */
        public int hardCapacity = 600;
        /** YAML value for drain mode. */
        /** Public field for drain mode. */
        public boolean drainMode = false;
        /** YAML value for metadata. */
        /** Public field for metadata. */
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

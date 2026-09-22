package dev.strataproxy.app;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Immutable runtime configuration for the proxy process.
 *
 * @param bindAddress frontend address that accepts Minecraft client connections
 * @param workerThreads Netty worker thread count; zero means derive from available processors
 * @param nativeTransport whether native Netty transport should be preferred
 * @param network low-level network limits and timeouts
 * @param registry backend registry and health-check configuration
 * @param compression compression negotiation and rewrite configuration
 * @param observability local runtime reporting configuration
 * @param status Minecraft status-ping response configuration
 * @param auth online-mode authentication configuration
 * @param forwarding backend forwarding protocol configuration
 * @param nativeRuntime native feature preferences
 */
public record ProxyConfig(
        InetSocketAddress bindAddress,
        int workerThreads,
        boolean nativeTransport,
        NetworkConfig network,
        RegistryConfig registry,
        CompressionConfig compression,
        ObservabilityConfig observability,
        StatusConfig status,
        AuthConfig auth,
        ForwardingConfig forwarding,
        NativeConfig nativeRuntime,
        BackendAgentConfig backendAgent) {
    /**
     * Compatibility constructor for callers that do not configure the backend-agent endpoint.
     */
    public ProxyConfig(
            InetSocketAddress bindAddress,
            int workerThreads,
            boolean nativeTransport,
            NetworkConfig network,
            RegistryConfig registry,
            CompressionConfig compression,
            ObservabilityConfig observability,
            StatusConfig status,
            AuthConfig auth,
            ForwardingConfig forwarding,
            NativeConfig nativeRuntime) {
        this(bindAddress, workerThreads, nativeTransport, network, registry, compression, observability, status, auth, forwarding, nativeRuntime, BackendAgentConfig.defaults());
    }
    /**
     * Validates and normalizes record components.
     */
    public ProxyConfig {
        if (workerThreads < 0) {
            throw new IllegalArgumentException("workerThreads must be >= 0");
        }
        network = network == null ? NetworkConfig.defaults() : network;
        registry = registry == null ? RegistryConfig.defaults() : registry;
        compression = compression == null ? CompressionConfig.defaults() : compression;
        observability = observability == null ? ObservabilityConfig.defaults() : observability;
        status = status == null ? StatusConfig.defaults() : status;
        auth = auth == null ? AuthConfig.defaults() : auth;
        forwarding = forwarding == null ? ForwardingConfig.defaults() : forwarding;
        nativeRuntime = nativeRuntime == null ? NativeConfig.defaults() : nativeRuntime;
        backendAgent = backendAgent == null ? BackendAgentConfig.defaults() : backendAgent;
    }

    /**
 * Documents this public API element.
 *
     * @return concrete worker thread count after resolving automatic mode
     */
    public int resolvedWorkerThreads() {
        return workerThreads == 0 ? Math.max(4, Runtime.getRuntime().availableProcessors()) : workerThreads;
    }

    /**
 * Documents this public API element.
 *
     * @return production defaults used when no config file exists
     */
    public static ProxyConfig defaults() {
        return new ProxyConfig(
                new InetSocketAddress("0.0.0.0", 25577),
                0,
                true,
                NetworkConfig.defaults(),
                RegistryConfig.defaults(),
                CompressionConfig.defaults(),
                ObservabilityConfig.defaults(),
                StatusConfig.defaults(),
                AuthConfig.defaults(),
                ForwardingConfig.defaults(),
                NativeConfig.defaults(),
                BackendAgentConfig.defaults());
    }

    /**
     * Dedicated, authenticated endpoint used only by backend agents such as the Bukkit adapter.
     * It is disabled by default and is not a general-purpose administration API.
     */
    public record BackendAgentConfig(
            boolean enabled,
            InetSocketAddress bindAddress,
            String sharedSecret,
            Duration heartbeatTimeout,
            int maxConnections,
            int maxQueuedConnections,
            int maxNonces) {
        public BackendAgentConfig {
            bindAddress = bindAddress == null ? new InetSocketAddress("127.0.0.1", 25578) : bindAddress;
            sharedSecret = sharedSecret == null ? "" : sharedSecret;
            heartbeatTimeout = heartbeatTimeout == null ? Duration.ofSeconds(30) : heartbeatTimeout;
            maxConnections = Math.max(1, maxConnections);
            maxQueuedConnections = Math.max(0, maxQueuedConnections);
            maxNonces = Math.max(1, maxNonces);
        }

        public static BackendAgentConfig defaults() {
            return new BackendAgentConfig(false, new InetSocketAddress("127.0.0.1", 25578), "", Duration.ofSeconds(30), 32, 64, 4_096);
        }
    }

    /**
     * Minecraft server-list status response settings.
     *
     * @param enabled whether status requests should be answered locally
     * @param motd status message of the day
     * @param protocolName display protocol name
     * @param protocolVersion protocol version advertised to clients; {@code -1} means runtime default
     * @param maxPlayers advertised max player count
     * @param favicon optional {@code data:image/png;base64,...} favicon
     * @param samplePlayers optional sample players shown in the status response
     */
    public record StatusConfig(
            boolean enabled,
            String motd,
            String protocolName,
            int protocolVersion,
            int maxPlayers,
            String favicon,
            List<StatusSamplePlayer> samplePlayers) {
        /**
         * Provides status config.
          * @param enabled enabled value
          * @param motd motd value
          * @param protocolName protocol name value
          * @param protocolVersion protocol version value
          * @param maxPlayers max players value
         */
        public StatusConfig(boolean enabled, String motd, String protocolName, int protocolVersion, int maxPlayers) {
            this(enabled, motd, protocolName, protocolVersion, maxPlayers, "", List.of());
        }

        /**
         * Validates and normalizes record components.
         */
        public StatusConfig {
            motd = motd == null || motd.isBlank() ? "StrataProxy" : motd;
            protocolName = protocolName == null || protocolName.isBlank() ? "StrataProxy" : protocolName;
            favicon = favicon == null ? "" : favicon;
            samplePlayers = samplePlayers == null ? List.of() : List.copyOf(samplePlayers);
        }

        /**
         * Provides defaults.
          * @return result of the operation
         */
        public static StatusConfig defaults() {
            return new StatusConfig(true, "StrataProxy", "StrataProxy", -1, 1000, "", List.of());
        }
    }

    /**
     * Sample player entry shown in the Minecraft status response.
     *
     * @param name displayed player name
     * @param id UUID string
     */
    public record StatusSamplePlayer(String name, String id) {
        /**
         * Validates and normalizes record components.
         */
        public StatusSamplePlayer {
            name = name == null ? "" : name;
            id = id == null || id.isBlank() ? "00000000-0000-0000-0000-000000000000" : id;
        }
    }

    /**
     * Online-mode authentication settings.
     *
     * @param onlineMode whether Minecraft online-mode encryption/authentication is enabled
     * @param rsaKeyBits RSA key size used during login encryption
     * @param verifyTokenBytes verify-token size used during login encryption
     * @param sessionVerification whether Mojang session verification is performed
     * @param sessionVerificationTimeout timeout for session verification calls
     */
    public record AuthConfig(
            boolean onlineMode,
            int rsaKeyBits,
            int verifyTokenBytes,
            boolean sessionVerification,
            Duration sessionVerificationTimeout) {
        /**
         * Validates and normalizes record components.
         */
        public AuthConfig {
            sessionVerificationTimeout = sessionVerificationTimeout == null ? Duration.ofSeconds(5) : sessionVerificationTimeout;
        }

        /**
         * Provides defaults.
          * @return result of the operation
         */
        public static AuthConfig defaults() {
            return new AuthConfig(false, 1024, 4, false, Duration.ofSeconds(5));
        }
    }

    /**
     * Backend player-forwarding protocol settings.
     *
     * @param mode forwarding mode such as {@code none}, {@code velocity-modern}, or {@code bungee-legacy}
     * @param secret forwarding secret when the selected mode requires one
     */
    public record ForwardingConfig(String mode, String secret) {
        /**
         * Validates and normalizes record components.
         */
        public ForwardingConfig {
            mode = mode == null || mode.isBlank() ? "none" : mode;
            secret = secret == null ? "" : secret;
        }

        /**
         * Provides defaults.
          * @return result of the operation
         */
        public static ForwardingConfig defaults() {
            return new ForwardingConfig("none", "");
        }
    }

    /**
     * Native runtime feature preferences from configuration.
     *
     * @param enabled whether native optimization is enabled
     * @param autoDetect whether detected native features are enabled automatically
     * @param preferNativeTransport whether native transport is preferred
     * @param requireNativeTransport whether native transport is mandatory
     * @param preferOpenSslTls whether OpenSSL TLS should be requested
     * @param preferNativeCompression whether native compression providers should be preferred
     * @param disabledFeatures feature labels to remove from the detected set
     * @param forcedFeatures feature labels to add even when not detected
     */
    public record NativeConfig(
            boolean enabled,
            boolean autoDetect,
            boolean preferNativeTransport,
            boolean requireNativeTransport,
            boolean preferOpenSslTls,
            boolean preferNativeCompression,
            Set<String> disabledFeatures,
            Set<String> forcedFeatures) {
        /**
         * Validates and normalizes record components.
         */
        public NativeConfig {
            disabledFeatures = disabledFeatures == null ? Set.of() : Set.copyOf(disabledFeatures);
            forcedFeatures = forcedFeatures == null ? Set.of() : Set.copyOf(forcedFeatures);
        }

        /**
         * Provides defaults.
          * @return result of the operation
         */
        public static NativeConfig defaults() {
            return new NativeConfig(true, true, true, false, false, false, Set.of(), Set.of());
        }
    }

    /**
     * Low-level network limits and timeouts.
     *
     * @param maxFrameBytes maximum inbound Minecraft frame size
     * @param connectTimeoutMillis backend connect timeout
     * @param writeBufferLowBytes Netty low write-buffer watermark
     * @param writeBufferHighBytes Netty high write-buffer watermark
     * @param maxConnections global concurrent connection limit
     * @param maxConnectionsPerAddress concurrent connection limit per client address
     * @param maxNewConnectionsPerSecond global admission rate limit; zero disables it
     * @param maxNewConnectionsPerAddressPerSecond per-address admission rate limit; zero disables it
     * @param initialHandshakeTimeoutMillis timeout for receiving the initial handshake
     * @param proxyProtocol whether HAProxy PROXY protocol v1 is accepted on frontend connections
     */
    public record NetworkConfig(
            int maxFrameBytes,
            int connectTimeoutMillis,
            int writeBufferLowBytes,
            int writeBufferHighBytes,
            int maxConnections,
            int maxConnectionsPerAddress,
            int maxNewConnectionsPerSecond,
            int maxNewConnectionsPerAddressPerSecond,
            int initialHandshakeTimeoutMillis,
            boolean proxyProtocol) {
        /**
         * Provides network config.
          * @param maxFrameBytes max frame bytes value
          * @param connectTimeoutMillis connect timeout millis value
          * @param writeBufferLowBytes write buffer low bytes value
          * @param writeBufferHighBytes write buffer high bytes value
          * @param maxConnections max connections value
          * @param maxConnectionsPerAddress max connections per address value
          * @param initialHandshakeTimeoutMillis initial handshake timeout millis value
         */
        public NetworkConfig(
                int maxFrameBytes,
                int connectTimeoutMillis,
                int writeBufferLowBytes,
                int writeBufferHighBytes,
                int maxConnections,
                int maxConnectionsPerAddress,
                int initialHandshakeTimeoutMillis) {
            this(
                    maxFrameBytes,
                    connectTimeoutMillis,
                    writeBufferLowBytes,
                    writeBufferHighBytes,
                    maxConnections,
                    maxConnectionsPerAddress,
                    0,
                    0,
                    initialHandshakeTimeoutMillis,
                    false);
        }

        /**
         * Provides network config.
          * @param maxFrameBytes max frame bytes value
          * @param connectTimeoutMillis connect timeout millis value
          * @param writeBufferLowBytes write buffer low bytes value
          * @param writeBufferHighBytes write buffer high bytes value
          * @param maxConnections max connections value
          * @param maxConnectionsPerAddress max connections per address value
          * @param initialHandshakeTimeoutMillis initial handshake timeout millis value
          * @param proxyProtocol proxy protocol value
         */
        public NetworkConfig(
                int maxFrameBytes,
                int connectTimeoutMillis,
                int writeBufferLowBytes,
                int writeBufferHighBytes,
                int maxConnections,
                int maxConnectionsPerAddress,
                int initialHandshakeTimeoutMillis,
                boolean proxyProtocol) {
            this(
                    maxFrameBytes,
                    connectTimeoutMillis,
                    writeBufferLowBytes,
                    writeBufferHighBytes,
                    maxConnections,
                    maxConnectionsPerAddress,
                    0,
                    0,
                    initialHandshakeTimeoutMillis,
                    proxyProtocol);
        }

        /**
         * Provides defaults.
          * @return result of the operation
         */
        public static NetworkConfig defaults() {
            return new NetworkConfig(
                    8 * 1024 * 1024,
                    5_000,
                    4 * 1024 * 1024,
                    16 * 1024 * 1024,
                    10_000,
                    200,
                    0,
                    0,
                    5_000,
                    false);
        }
    }

    /**
     * Backend registry and health-check configuration.
     *
     * @param staticServers whether static server entries from config are loaded
     * @param persistenceEnabled whether dynamic registry changes are persisted
     * @param persistencePath path for registry persistence
     * @param healthCheckEnabled whether background health checks are enabled
     * @param healthCheckInterval interval between health checks
     * @param healthCheckTimeout timeout for one health check
     * @param healthCheckMode health-check strategy, such as {@code tcp} or {@code minecraft-status}
     */
    public record RegistryConfig(
            boolean staticServers,
            boolean persistenceEnabled,
            String persistencePath,
            boolean healthCheckEnabled,
            Duration healthCheckInterval,
            Duration healthCheckTimeout,
            String healthCheckMode) {
        /**
         * Provides registry config.
          * @param staticServers static servers value
          * @param persistenceEnabled persistence enabled value
          * @param persistencePath persistence path value
          * @param healthCheckEnabled health check enabled value
          * @param healthCheckInterval health check interval value
          * @param healthCheckTimeout health check timeout value
         */
        public RegistryConfig(
                boolean staticServers,
                boolean persistenceEnabled,
                String persistencePath,
                boolean healthCheckEnabled,
                Duration healthCheckInterval,
                Duration healthCheckTimeout) {
            this(staticServers, persistenceEnabled, persistencePath, healthCheckEnabled, healthCheckInterval, healthCheckTimeout, "tcp");
        }

        /**
         * Validates and normalizes record components.
         */
        public RegistryConfig {
            persistencePath = persistencePath == null ? "" : persistencePath;
            healthCheckInterval = healthCheckInterval == null ? Duration.ofSeconds(5) : healthCheckInterval;
            healthCheckTimeout = healthCheckTimeout == null ? Duration.ofSeconds(2) : healthCheckTimeout;
            healthCheckMode = healthCheckMode == null || healthCheckMode.isBlank() ? "tcp" : healthCheckMode;
        }

        /**
         * Provides defaults.
          * @return result of the operation
         */
        public static RegistryConfig defaults() {
            return new RegistryConfig(true, true, "data/registry.json", true, Duration.ofSeconds(5), Duration.ofSeconds(2), "tcp");
        }
    }

    /**
     * Compression negotiation and compressed-frame rewrite settings.
     *
     * @param mode compression strategy mode
     * @param minThreshold minimum compression threshold
     * @param maxThreshold maximum compression threshold for adaptive mode
     * @param cpuGuard CPU load at which compression becomes conservative
     * @param rewriteEnabled whether compressed frames may be rewritten between thresholds
     * @param rewriteMaxEventLoopDelayMillis event-loop delay limit for rewrite work
     */
    public record CompressionConfig(
            String mode,
            int minThreshold,
            int maxThreshold,
            double cpuGuard,
            boolean rewriteEnabled,
            int rewriteMaxEventLoopDelayMillis) {
        /**
         * Provides compression config.
          * @param mode mode value
          * @param minThreshold min threshold value
          * @param maxThreshold max threshold value
          * @param cpuGuard cpu guard value
         */
        public CompressionConfig(String mode, int minThreshold, int maxThreshold, double cpuGuard) {
            this(mode, minThreshold, maxThreshold, cpuGuard, false, 25);
        }

        /**
         * Provides compression config.
          * @param mode mode value
          * @param minThreshold min threshold value
          * @param maxThreshold max threshold value
          * @param cpuGuard cpu guard value
          * @param rewriteEnabled rewrite enabled value
         */
        public CompressionConfig(String mode, int minThreshold, int maxThreshold, double cpuGuard, boolean rewriteEnabled) {
            this(mode, minThreshold, maxThreshold, cpuGuard, rewriteEnabled, 25);
        }

        /**
         * Provides defaults.
          * @return result of the operation
         */
        public static CompressionConfig defaults() {
            return new CompressionConfig("adaptive", 256, 8192, 0.75d, false, 25);
        }
    }

    /**
     * Local runtime reporting settings.
     *
     * @param flushInterval registry load-reporting flush interval
     */
    public record ObservabilityConfig(Duration flushInterval) {
        /**
         * Provides defaults.
          * @return result of the operation
         */
        public static ObservabilityConfig defaults() {
            return new ObservabilityConfig(Duration.ofSeconds(5));
        }
    }
}

package dev.strataproxy.bootstrap;

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
 * @param packetAnalysis packet inspection and anomaly thresholds
 * @param observability metrics and event sampling configuration
 * @param admin admin HTTP API configuration
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
        PacketAnalysisConfig packetAnalysis,
        ObservabilityConfig observability,
        AdminConfig admin,
        StatusConfig status,
        AuthConfig auth,
        ForwardingConfig forwarding,
        NativeConfig nativeRuntime) {
    public ProxyConfig(
            InetSocketAddress bindAddress,
            int workerThreads,
            boolean nativeTransport,
            NetworkConfig network,
            RegistryConfig registry,
            CompressionConfig compression,
            PacketAnalysisConfig packetAnalysis,
            ObservabilityConfig observability,
            AdminConfig admin) {
        this(
                bindAddress,
                workerThreads,
                nativeTransport,
                network,
                registry,
                compression,
                packetAnalysis,
                observability,
                admin,
                StatusConfig.defaults(),
                AuthConfig.defaults(),
                ForwardingConfig.defaults(),
                NativeConfig.defaults());
    }

    public ProxyConfig(
            InetSocketAddress bindAddress,
            int workerThreads,
            boolean nativeTransport,
            NetworkConfig network,
            RegistryConfig registry,
            CompressionConfig compression,
            PacketAnalysisConfig packetAnalysis,
            ObservabilityConfig observability,
            AdminConfig admin,
            NativeConfig nativeRuntime) {
        this(
                bindAddress,
                workerThreads,
                nativeTransport,
                network,
                registry,
                compression,
                packetAnalysis,
                observability,
                admin,
                StatusConfig.defaults(),
                AuthConfig.defaults(),
                ForwardingConfig.defaults(),
                nativeRuntime);
    }

    public ProxyConfig(
            InetSocketAddress bindAddress,
            int workerThreads,
            boolean nativeTransport,
            NetworkConfig network,
            RegistryConfig registry,
            CompressionConfig compression,
            PacketAnalysisConfig packetAnalysis,
            ObservabilityConfig observability,
            AdminConfig admin,
            AuthConfig auth,
            ForwardingConfig forwarding,
            NativeConfig nativeRuntime) {
        this(
                bindAddress,
                workerThreads,
                nativeTransport,
                network,
                registry,
                compression,
                packetAnalysis,
                observability,
                admin,
                StatusConfig.defaults(),
                auth,
                forwarding,
                nativeRuntime);
    }

    public ProxyConfig {
        if (workerThreads < 0) {
            throw new IllegalArgumentException("workerThreads must be >= 0");
        }
        network = network == null ? NetworkConfig.defaults() : network;
        registry = registry == null ? RegistryConfig.defaults() : registry;
        compression = compression == null ? CompressionConfig.defaults() : compression;
        packetAnalysis = packetAnalysis == null ? PacketAnalysisConfig.defaults() : packetAnalysis;
        observability = observability == null ? ObservabilityConfig.defaults() : observability;
        admin = admin == null ? AdminConfig.defaults() : admin;
        status = status == null ? StatusConfig.defaults() : status;
        auth = auth == null ? AuthConfig.defaults() : auth;
        forwarding = forwarding == null ? ForwardingConfig.defaults() : forwarding;
        nativeRuntime = nativeRuntime == null ? NativeConfig.defaults() : nativeRuntime;
    }

    /**
     * @return concrete worker thread count after resolving automatic mode
     */
    public int resolvedWorkerThreads() {
        return workerThreads == 0 ? Math.max(4, Runtime.getRuntime().availableProcessors()) : workerThreads;
    }

    /**
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
                PacketAnalysisConfig.defaults(),
                ObservabilityConfig.defaults(),
                AdminConfig.defaults(),
                StatusConfig.defaults(),
                AuthConfig.defaults(),
                ForwardingConfig.defaults(),
                NativeConfig.defaults());
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
        public StatusConfig(boolean enabled, String motd, String protocolName, int protocolVersion, int maxPlayers) {
            this(enabled, motd, protocolName, protocolVersion, maxPlayers, "", List.of());
        }

        public StatusConfig {
            motd = motd == null || motd.isBlank() ? "StrataProxy" : motd;
            protocolName = protocolName == null || protocolName.isBlank() ? "StrataProxy" : protocolName;
            favicon = favicon == null ? "" : favicon;
            samplePlayers = samplePlayers == null ? List.of() : List.copyOf(samplePlayers);
        }

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
        public AuthConfig {
            sessionVerificationTimeout = sessionVerificationTimeout == null ? Duration.ofSeconds(5) : sessionVerificationTimeout;
        }

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
        public ForwardingConfig {
            mode = mode == null || mode.isBlank() ? "none" : mode;
            secret = secret == null ? "" : secret;
        }

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
        public NativeConfig {
            disabledFeatures = disabledFeatures == null ? Set.of() : Set.copyOf(disabledFeatures);
            forcedFeatures = forcedFeatures == null ? Set.of() : Set.copyOf(forcedFeatures);
        }

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
        public RegistryConfig(
                boolean staticServers,
                boolean persistenceEnabled,
                String persistencePath,
                boolean healthCheckEnabled,
                Duration healthCheckInterval,
                Duration healthCheckTimeout) {
            this(staticServers, persistenceEnabled, persistencePath, healthCheckEnabled, healthCheckInterval, healthCheckTimeout, "tcp");
        }

        public RegistryConfig {
            persistencePath = persistencePath == null ? "" : persistencePath;
            healthCheckInterval = healthCheckInterval == null ? Duration.ofSeconds(5) : healthCheckInterval;
            healthCheckTimeout = healthCheckTimeout == null ? Duration.ofSeconds(2) : healthCheckTimeout;
            healthCheckMode = healthCheckMode == null || healthCheckMode.isBlank() ? "tcp" : healthCheckMode;
        }

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
     * @param codec compression codec label, currently {@code zlib} or {@code zstd}
     * @param zstdLevel zstd compression level
     * @param zstdDictionaryPath optional zstd dictionary path
     */
    public record CompressionConfig(
            String mode,
            int minThreshold,
            int maxThreshold,
            double cpuGuard,
            boolean rewriteEnabled,
            int rewriteMaxEventLoopDelayMillis,
            String codec,
            int zstdLevel,
            String zstdDictionaryPath) {
        public CompressionConfig(String mode, int minThreshold, int maxThreshold, double cpuGuard) {
            this(mode, minThreshold, maxThreshold, cpuGuard, false, 25);
        }

        public CompressionConfig(String mode, int minThreshold, int maxThreshold, double cpuGuard, boolean rewriteEnabled) {
            this(mode, minThreshold, maxThreshold, cpuGuard, rewriteEnabled, 25);
        }

        public CompressionConfig(
                String mode,
                int minThreshold,
                int maxThreshold,
                double cpuGuard,
                boolean rewriteEnabled,
                int rewriteMaxEventLoopDelayMillis) {
            this(mode, minThreshold, maxThreshold, cpuGuard, rewriteEnabled, rewriteMaxEventLoopDelayMillis, "zlib", 1, "");
        }

        public CompressionConfig {
            codec = codec == null || codec.isBlank() ? "zlib" : codec;
            zstdDictionaryPath = zstdDictionaryPath == null ? "" : zstdDictionaryPath;
        }

        public static CompressionConfig defaults() {
            return new CompressionConfig("adaptive", 256, 8192, 0.75d, false, 25, "zlib", 1, "");
        }
    }

    /**
     * Packet anomaly thresholds.
     *
     * @param largePayloadWarnBytes size at which custom payloads emit warnings
     * @param unknownChannelThrottleBytes size at which unknown channels should be throttled
     * @param moddedHandshakeWarnBytes size at which modded handshakes emit warnings
     * @param customPayloadFloodMaxCount allowed custom-payload count in the flood window
     * @param customPayloadFloodWindow flood detection window
     */
    public record PacketAnalysisConfig(
            int largePayloadWarnBytes,
            int unknownChannelThrottleBytes,
            int moddedHandshakeWarnBytes,
            int customPayloadFloodMaxCount,
            Duration customPayloadFloodWindow) {
        public PacketAnalysisConfig {
            customPayloadFloodWindow = customPayloadFloodWindow == null ? Duration.ofSeconds(10) : customPayloadFloodWindow;
        }

        public static PacketAnalysisConfig defaults() {
            return new PacketAnalysisConfig(1024 * 1024, 256 * 1024, 2 * 1024 * 1024, 200, Duration.ofSeconds(10));
        }
    }

    /**
     * Observability settings.
     *
     * @param prometheus whether Prometheus metrics are exposed
     * @param packetTopN number of top packet rows retained for reports
     * @param anomalySampling whether packet anomaly samples are retained
     * @param flushInterval event flush interval
     */
    public record ObservabilityConfig(boolean prometheus, int packetTopN, boolean anomalySampling, Duration flushInterval) {
        public static ObservabilityConfig defaults() {
            return new ObservabilityConfig(true, 50, true, Duration.ofSeconds(5));
        }
    }

    /**
     * Admin HTTP API settings.
     *
     * @param enabled whether the admin API server should start
     * @param bindAddress admin API bind address
     * @param bearerToken bearer token required for protected endpoints; blank disables token auth
     * @param tls TLS configuration for the admin API
     */
    public record AdminConfig(boolean enabled, InetSocketAddress bindAddress, String bearerToken, AdminTlsConfig tls) {
        public AdminConfig {
            bearerToken = bearerToken == null ? "" : bearerToken;
            tls = tls == null ? AdminTlsConfig.defaults() : tls;
        }

        public AdminConfig(boolean enabled, InetSocketAddress bindAddress, String bearerToken) {
            this(enabled, bindAddress, bearerToken, AdminTlsConfig.defaults());
        }

        public static AdminConfig defaults() {
            return new AdminConfig(true, new InetSocketAddress("127.0.0.1", 8080), "", AdminTlsConfig.defaults());
        }
    }

    /**
     * Admin API TLS and optional client-auth settings.
     *
     * @param enabled whether admin API TLS is enabled
     * @param keyStorePath server key store path
     * @param keyStorePassword server key store password
     * @param keyStoreType server key store type
     * @param trustStorePath trust store path for client certificates
     * @param trustStorePassword trust store password
     * @param trustStoreType trust store type
     * @param clientAuth whether client certificates are required
     */
    public record AdminTlsConfig(
            boolean enabled,
            String keyStorePath,
            String keyStorePassword,
            String keyStoreType,
            String trustStorePath,
            String trustStorePassword,
            String trustStoreType,
            boolean clientAuth) {
        public AdminTlsConfig {
            keyStorePath = keyStorePath == null ? "" : keyStorePath;
            keyStorePassword = keyStorePassword == null ? "" : keyStorePassword;
            keyStoreType = keyStoreType == null || keyStoreType.isBlank() ? "PKCS12" : keyStoreType;
            trustStorePath = trustStorePath == null ? "" : trustStorePath;
            trustStorePassword = trustStorePassword == null ? "" : trustStorePassword;
            trustStoreType = trustStoreType == null || trustStoreType.isBlank() ? "PKCS12" : trustStoreType;
        }

        public static AdminTlsConfig defaults() {
            return new AdminTlsConfig(false, "", "", "PKCS12", "", "", "PKCS12", false);
        }
    }
}

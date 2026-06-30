package dev.strataproxy.bootstrap;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Set;

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
        AuthConfig auth,
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
                AuthConfig.defaults(),
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
                AuthConfig.defaults(),
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
        auth = auth == null ? AuthConfig.defaults() : auth;
        nativeRuntime = nativeRuntime == null ? NativeConfig.defaults() : nativeRuntime;
    }

    public int resolvedWorkerThreads() {
        return workerThreads == 0 ? Math.max(4, Runtime.getRuntime().availableProcessors()) : workerThreads;
    }

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
                AuthConfig.defaults(),
                NativeConfig.defaults());
    }

    public record AuthConfig(
            boolean onlineMode,
            int rsaKeyBits,
            int verifyTokenBytes,
            boolean sessionVerification) {
        public static AuthConfig defaults() {
            return new AuthConfig(false, 1024, 4, false);
        }
    }

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

    public record NetworkConfig(
            int maxFrameBytes,
            int connectTimeoutMillis,
            int writeBufferLowBytes,
            int writeBufferHighBytes,
            int maxConnections,
            int maxConnectionsPerAddress,
            int initialHandshakeTimeoutMillis) {
        public static NetworkConfig defaults() {
            return new NetworkConfig(8 * 1024 * 1024, 5_000, 4 * 1024 * 1024, 16 * 1024 * 1024, 10_000, 200, 5_000);
        }
    }

    public record RegistryConfig(
            boolean staticServers,
            boolean persistenceEnabled,
            String persistencePath,
            boolean healthCheckEnabled,
            Duration healthCheckInterval,
            Duration healthCheckTimeout) {
        public RegistryConfig {
            persistencePath = persistencePath == null ? "" : persistencePath;
            healthCheckInterval = healthCheckInterval == null ? Duration.ofSeconds(5) : healthCheckInterval;
            healthCheckTimeout = healthCheckTimeout == null ? Duration.ofSeconds(2) : healthCheckTimeout;
        }

        public static RegistryConfig defaults() {
            return new RegistryConfig(true, true, "data/registry.json", true, Duration.ofSeconds(5), Duration.ofSeconds(2));
        }
    }

    public record CompressionConfig(
            String mode,
            int minThreshold,
            int maxThreshold,
            double cpuGuard,
            boolean rewriteEnabled,
            int rewriteMaxEventLoopDelayMillis) {
        public CompressionConfig(String mode, int minThreshold, int maxThreshold, double cpuGuard) {
            this(mode, minThreshold, maxThreshold, cpuGuard, false, 25);
        }

        public CompressionConfig(String mode, int minThreshold, int maxThreshold, double cpuGuard, boolean rewriteEnabled) {
            this(mode, minThreshold, maxThreshold, cpuGuard, rewriteEnabled, 25);
        }

        public static CompressionConfig defaults() {
            return new CompressionConfig("adaptive", 256, 8192, 0.75d, false, 25);
        }
    }

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

    public record ObservabilityConfig(boolean prometheus, int packetTopN, boolean anomalySampling, Duration flushInterval) {
        public static ObservabilityConfig defaults() {
            return new ObservabilityConfig(true, 50, true, Duration.ofSeconds(5));
        }
    }

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

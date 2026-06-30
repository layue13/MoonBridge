package dev.strataproxy.nativefeature;

import java.util.EnumSet;
import java.util.Set;

public record NativeRuntimeDecision(
        boolean enabled,
        NativeCapabilities capabilities,
        Set<NativeFeature> enabledFeatures,
        boolean preferNativeTransport,
        boolean requireNativeTransport,
        String tlsProvider,
        String compressionProvider) {
    public NativeRuntimeDecision {
        enabledFeatures = enabledFeatures == null ? Set.of() : Set.copyOf(enabledFeatures);
    }

    public static NativeRuntimeDecision resolve(NativeRuntimeOptions options, NativeCapabilities detected) {
        var opts = options == null ? NativeRuntimeOptions.defaults() : options;
        var capabilities = detected == null ? NativeCapabilityDetector.detect() : detected;
        if (!opts.enabled()) {
            return new NativeRuntimeDecision(
                    false,
                    capabilities,
                    Set.of(),
                    false,
                    opts.requireNativeTransport(),
                    "jdk",
                    "jdk-deflater");
        }
        var enabled = EnumSet.noneOf(NativeFeature.class);
        if (opts.autoDetect()) {
            enabled.addAll(capabilities.features());
        }
        enabled.addAll(opts.forcedFeatures());
        enabled.removeAll(opts.disabledFeatures());
        var tlsProvider = opts.preferOpenSslTls() ? "openssl-requested-jdk-active" : capabilities.preferredTlsProvider();
        var compressionProvider = opts.preferNativeCompression() ? capabilities.preferredCompressionProvider() : "jdk-deflater";
        return new NativeRuntimeDecision(
                true,
                capabilities,
                Set.copyOf(enabled),
                opts.preferNativeTransport(),
                opts.requireNativeTransport(),
                tlsProvider,
                compressionProvider);
    }
}

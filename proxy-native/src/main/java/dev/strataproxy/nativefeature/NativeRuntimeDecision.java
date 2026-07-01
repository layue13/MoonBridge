package dev.strataproxy.nativefeature;

import java.util.EnumSet;
import java.util.Set;

/**
 * Effective native-runtime configuration after applying options to detected capabilities.
 *
 * @param enabled whether native optimization is enabled at all
 * @param capabilities detected host capabilities
 * @param enabledFeatures features enabled after auto-detect, forced, and disabled filters
 * @param preferNativeTransport whether native network transport should be preferred
 * @param requireNativeTransport whether startup should fail if native transport is unavailable
 * @param tlsProvider selected TLS provider label
 * @param compressionProvider selected compression provider label
 */
public record NativeRuntimeDecision(
        boolean enabled,
        NativeCapabilities capabilities,
        Set<NativeFeature> enabledFeatures,
        boolean preferNativeTransport,
        boolean requireNativeTransport,
        String tlsProvider,
        String compressionProvider) {
    /**
     * Validates and normalizes record components.
     */
    public NativeRuntimeDecision {
        enabledFeatures = enabledFeatures == null ? Set.of() : Set.copyOf(enabledFeatures);
    }

    /**
     * Resolves runtime behavior from user options and detected host capabilities.
     *
     * @param options user-configured options; defaults are used when {@code null}
     * @param detected detected capabilities; detection is run when {@code null}
     * @return effective runtime decision
     */
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

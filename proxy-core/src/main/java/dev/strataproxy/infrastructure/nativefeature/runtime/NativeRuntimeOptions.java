package dev.strataproxy.infrastructure.nativefeature.runtime;

import java.util.Set;

/**
 * User-configurable native-runtime preferences.
 *
 * @param enabled whether native optimization is enabled
 * @param autoDetect whether detected CPU features should be enabled automatically
 * @param preferNativeTransport whether native network transport should be preferred
 * @param requireNativeTransport whether native transport is mandatory
 * @param preferOpenSslTls whether OpenSSL TLS should be requested when available
 * @param preferNativeCompression whether native compression providers should be preferred
 * @param disabledFeatures features to remove from the detected set
 * @param forcedFeatures features to enable even when detection did not find them
 */
public record NativeRuntimeOptions(
        boolean enabled,
        boolean autoDetect,
        boolean preferNativeTransport,
        boolean requireNativeTransport,
        boolean preferOpenSslTls,
        boolean preferNativeCompression,
        Set<NativeFeature> disabledFeatures,
        Set<NativeFeature> forcedFeatures) {
    /**
     * Validates and normalizes record components.
     */
    public NativeRuntimeOptions {
        disabledFeatures = disabledFeatures == null ? Set.of() : Set.copyOf(disabledFeatures);
        forcedFeatures = forcedFeatures == null ? Set.of() : Set.copyOf(forcedFeatures);
    }

    /**
 * Documents this public API element.
 *
     * @return default runtime options for production startup
     */
    public static NativeRuntimeOptions defaults() {
        return new NativeRuntimeOptions(true, true, true, false, false, false, Set.of(), Set.of());
    }
}

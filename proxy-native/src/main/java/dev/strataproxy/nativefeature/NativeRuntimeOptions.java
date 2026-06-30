package dev.strataproxy.nativefeature;

import java.util.Set;

public record NativeRuntimeOptions(
        boolean enabled,
        boolean autoDetect,
        boolean preferNativeTransport,
        boolean requireNativeTransport,
        boolean preferOpenSslTls,
        boolean preferNativeCompression,
        Set<NativeFeature> disabledFeatures,
        Set<NativeFeature> forcedFeatures) {
    public NativeRuntimeOptions {
        disabledFeatures = disabledFeatures == null ? Set.of() : Set.copyOf(disabledFeatures);
        forcedFeatures = forcedFeatures == null ? Set.of() : Set.copyOf(forcedFeatures);
    }

    public static NativeRuntimeOptions defaults() {
        return new NativeRuntimeOptions(true, true, true, false, false, false, Set.of(), Set.of());
    }
}

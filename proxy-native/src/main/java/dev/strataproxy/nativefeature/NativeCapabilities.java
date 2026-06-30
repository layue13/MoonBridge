package dev.strataproxy.nativefeature;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public record NativeCapabilities(
        String os,
        String arch,
        Set<NativeFeature> features,
        String detectionSource,
        String preferredTlsProvider,
        String preferredCompressionProvider) {
    public NativeCapabilities {
        os = os == null || os.isBlank() ? "unknown" : os;
        arch = arch == null || arch.isBlank() ? "unknown" : arch;
        features = features == null ? Set.of() : Set.copyOf(features);
        detectionSource = detectionSource == null || detectionSource.isBlank() ? "unknown" : detectionSource;
        preferredTlsProvider = preferredTlsProvider == null || preferredTlsProvider.isBlank() ? "jdk" : preferredTlsProvider;
        preferredCompressionProvider = preferredCompressionProvider == null || preferredCompressionProvider.isBlank() ? "jdk-deflater" : preferredCompressionProvider;
    }

    public boolean has(NativeFeature feature) {
        return features.contains(feature);
    }

    public Map<String, Boolean> featureMap(Set<NativeFeature> enabledFeatures) {
        var enabled = enabledFeatures == null ? features : enabledFeatures;
        return java.util.Arrays.stream(NativeFeature.values())
                .collect(Collectors.toUnmodifiableMap(NativeFeature::label, enabled::contains));
    }
}

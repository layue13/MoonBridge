package dev.strataproxy.nativefeature;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Native CPU and runtime capabilities detected for the current host.
 *
 * @param os operating-system name
 * @param arch CPU architecture
 * @param features detected native instruction or platform features
 * @param detectionSource source used to detect features
 * @param preferredTlsProvider preferred TLS implementation label
 * @param preferredCompressionProvider preferred compression implementation label
 */
public record NativeCapabilities(
        String os,
        String arch,
        Set<NativeFeature> features,
        String detectionSource,
        String preferredTlsProvider,
        String preferredCompressionProvider) {
    /**
     * Validates and normalizes record components.
     */
    public NativeCapabilities {
        os = os == null || os.isBlank() ? "unknown" : os;
        arch = arch == null || arch.isBlank() ? "unknown" : arch;
        features = features == null ? Set.of() : Set.copyOf(features);
        detectionSource = detectionSource == null || detectionSource.isBlank() ? "unknown" : detectionSource;
        preferredTlsProvider = preferredTlsProvider == null || preferredTlsProvider.isBlank() ? "jdk" : preferredTlsProvider;
        preferredCompressionProvider = preferredCompressionProvider == null || preferredCompressionProvider.isBlank() ? "jdk-deflater" : preferredCompressionProvider;
    }

    /**
 * Documents this public API element.
 *
     * @param feature feature to test
     * @return {@code true} when the feature was detected
     */
    public boolean has(NativeFeature feature) {
        return features.contains(feature);
    }

    /**
     * Builds a stable map of all known features to enabled states.
     *
     * @param enabledFeatures feature set to render, or detected features when {@code null}
     * @return map keyed by {@link NativeFeature#label()}
     */
    public Map<String, Boolean> featureMap(Set<NativeFeature> enabledFeatures) {
        var enabled = enabledFeatures == null ? features : enabledFeatures;
        return java.util.Arrays.stream(NativeFeature.values())
                .collect(Collectors.toUnmodifiableMap(NativeFeature::label, enabled::contains));
    }
}

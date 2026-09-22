package dev.strataproxy.nativefeature;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NativeRuntimeDecisionTest {
    @Test
    void resolvesAutoDetectedFeaturesWithManualOverrides() {
        var capabilities = new NativeCapabilities(
                "Linux",
                "amd64",
                Set.of(NativeFeature.AES, NativeFeature.AVX2, NativeFeature.AVX512F),
                "/proc/cpuinfo",
                "jdk-aes-intrinsics",
                "jdk-deflater-native-zlib");
        var options = new NativeRuntimeOptions(
                true,
                true,
                true,
                true,
                false,
                true,
                Set.of(NativeFeature.AVX512F),
                Set.of(NativeFeature.BMI2));

        var decision = NativeRuntimeDecision.resolve(options, capabilities);

        assertTrue(decision.enabled());
        assertTrue(decision.preferNativeTransport());
        assertTrue(decision.requireNativeTransport());
        assertTrue(decision.enabledFeatures().contains(NativeFeature.AES));
        assertTrue(decision.enabledFeatures().contains(NativeFeature.AVX2));
        assertTrue(decision.enabledFeatures().contains(NativeFeature.BMI2));
        assertFalse(decision.enabledFeatures().contains(NativeFeature.AVX512F));
        assertEquals("jdk-aes-intrinsics", decision.tlsProvider());
        assertEquals("jdk-deflater-native-zlib", decision.compressionProvider());
    }

    @Test
    void disablesNativeRuntimeWithoutLosingDetectedCapabilitySnapshot() {
        var capabilities = new NativeCapabilities(
                "Linux",
                "amd64",
                Set.of(NativeFeature.AES),
                "/proc/cpuinfo",
                "jdk-aes-intrinsics",
                "jdk-deflater");

        var decision = NativeRuntimeDecision.resolve(
                new NativeRuntimeOptions(false, true, true, false, false, false, Set.of(), Set.of()),
                capabilities);

        assertFalse(decision.enabled());
        assertFalse(decision.preferNativeTransport());
        assertTrue(decision.enabledFeatures().isEmpty());
        assertEquals("Linux", decision.capabilities().os());
        assertEquals("jdk", decision.tlsProvider());
    }
}

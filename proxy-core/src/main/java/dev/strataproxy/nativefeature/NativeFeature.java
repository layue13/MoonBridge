package dev.strataproxy.nativefeature;

import java.util.Locale;

/**
 * CPU or platform feature that can influence native transport, TLS, or compression choices.
 */
public enum NativeFeature {
    /** Native feature constant for aes. */
    AES("aes"),
    /** Native feature constant for vaes. */
    VAES("vaes"),
    /** Native feature constant for pclmulqdq. */
    PCLMULQDQ("pclmulqdq"),
    /** Native feature constant for vpclmulqdq. */
    VPCLMULQDQ("vpclmulqdq"),
    /** Native feature constant for sha ni. */
    SHA_NI("sha_ni"),
    /** Native feature constant for crc32. */
    CRC32("crc32"),
    /** Native feature constant for sse4 2. */
    SSE4_2("sse4_2"),
    /** Native feature constant for avx2. */
    AVX2("avx2"),
    /** Native feature constant for avx512f. */
    AVX512F("avx512f"),
    /** Native feature constant for bmi1. */
    BMI1("bmi1"),
    /** Native feature constant for bmi2. */
    BMI2("bmi2"),
    /** Native feature constant for lzcnt. */
    LZCNT("lzcnt"),
    /** Native feature constant for popcnt. */
    POPCNT("popcnt"),
    /** Native feature constant for neon. */
    NEON("neon"),
    /** Native feature constant for arm aes. */
    ARM_AES("arm_aes"),
    /** Native feature constant for arm sha. */
    ARM_SHA("arm_sha");

    private final String label;

    NativeFeature(String label) {
        this.label = label;
    }

    /**
 * Documents this public API element.
 *
     * @return stable lowercase label used in configuration and diagnostics
     */
    public String label() {
        return label;
    }

    /**
     * Resolves a feature from a label or enum name.
     *
     * @param value label such as {@code avx2} or {@code arm-aes}
     * @return matching native feature
     * @throws IllegalArgumentException when the value is blank or unknown
     */
    public static NativeFeature fromLabel(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("native feature must not be blank");
        }
        var normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        for (var feature : values()) {
            if (feature.label.equals(normalized) || feature.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return feature;
            }
        }
        throw new IllegalArgumentException("unknown native feature: " + value);
    }
}

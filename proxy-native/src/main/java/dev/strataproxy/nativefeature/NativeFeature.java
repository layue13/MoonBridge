package dev.strataproxy.nativefeature;

import java.util.Locale;

/**
 * CPU or platform feature that can influence native transport, TLS, or compression choices.
 */
public enum NativeFeature {
    AES("aes"),
    VAES("vaes"),
    PCLMULQDQ("pclmulqdq"),
    VPCLMULQDQ("vpclmulqdq"),
    SHA_NI("sha_ni"),
    CRC32("crc32"),
    SSE4_2("sse4_2"),
    AVX2("avx2"),
    AVX512F("avx512f"),
    BMI1("bmi1"),
    BMI2("bmi2"),
    LZCNT("lzcnt"),
    POPCNT("popcnt"),
    NEON("neon"),
    ARM_AES("arm_aes"),
    ARM_SHA("arm_sha");

    private final String label;

    NativeFeature(String label) {
        this.label = label;
    }

    /**
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

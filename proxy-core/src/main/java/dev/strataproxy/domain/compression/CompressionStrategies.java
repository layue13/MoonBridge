package dev.strataproxy.domain.compression;

import java.util.Locale;

/**
 * Factory for built-in compression strategies.
 */
public final class CompressionStrategies {
    private CompressionStrategies() {
    }

    /**
     * Resolves a strategy from a configuration mode.
     *
     * @param mode supported values are {@code off}, {@code fixed}, and {@code adaptive}
     * @return compression strategy instance
     * @throws IllegalArgumentException when the mode is unknown
     */
    public static CompressionStrategy from(String mode) {
        return switch (normalize(mode)) {
            case "off" -> new DisabledCompressionStrategy();
            case "fixed" -> new FixedCompressionStrategy();
            case "adaptive" -> new AdaptiveCompressionStrategy();
            default -> throw new IllegalArgumentException("unsupported compression mode: " + mode);
        };
    }

    private static String normalize(String mode) {
        return mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
    }
}

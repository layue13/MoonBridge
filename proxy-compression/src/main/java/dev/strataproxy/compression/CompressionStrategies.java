package dev.strataproxy.compression;

import java.util.Locale;

public final class CompressionStrategies {
    private CompressionStrategies() {
    }

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

package dev.strataproxy.nativefeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public final class NativeCapabilityDetector {
    private NativeCapabilityDetector() {
    }

    public static NativeCapabilities detect() {
        var os = System.getProperty("os.name", "unknown");
        var arch = System.getProperty("os.arch", "unknown");
        var features = EnumSet.noneOf(NativeFeature.class);
        var source = "java-system-properties";
        var normalizedArch = arch.toLowerCase(Locale.ROOT);
        if (normalizedArch.contains("aarch64") || normalizedArch.contains("arm64")) {
            features.add(NativeFeature.NEON);
        }
        if (normalizedArch.contains("amd64") || normalizedArch.contains("x86_64")) {
            maybeAddLinuxCpuInfo(features, true);
            if (!features.isEmpty()) {
                source = "/proc/cpuinfo";
            }
        } else if (normalizedArch.contains("aarch64") || normalizedArch.contains("arm")) {
            if (maybeAddLinuxCpuInfo(features, false)) {
                source = "/proc/cpuinfo";
            }
        }
        return new NativeCapabilities(
                os,
                arch,
                Set.copyOf(features),
                source,
                selectTlsProvider(features),
                selectCompressionProvider(features));
    }

    private static boolean maybeAddLinuxCpuInfo(EnumSet<NativeFeature> features, boolean x86) {
        var path = Path.of("/proc/cpuinfo");
        if (Files.notExists(path)) {
            return false;
        }
        try {
            var text = Files.readString(path).toLowerCase(Locale.ROOT);
            if (x86) {
                addIfPresent(features, text, " aes ", NativeFeature.AES);
                addIfPresent(features, text, " vaes ", NativeFeature.VAES);
                addIfPresent(features, text, " pclmulqdq ", NativeFeature.PCLMULQDQ);
                addIfPresent(features, text, " vpclmulqdq ", NativeFeature.VPCLMULQDQ);
                addIfPresent(features, text, " sha_ni ", NativeFeature.SHA_NI);
                addIfPresent(features, text, " crc32 ", NativeFeature.CRC32);
                addIfPresent(features, text, " sse4_2 ", NativeFeature.SSE4_2);
                addIfPresent(features, text, " avx2 ", NativeFeature.AVX2);
                addIfPresent(features, text, " avx512f ", NativeFeature.AVX512F);
                addIfPresent(features, text, " bmi1 ", NativeFeature.BMI1);
                addIfPresent(features, text, " bmi2 ", NativeFeature.BMI2);
                addIfPresent(features, text, " lzcnt ", NativeFeature.LZCNT);
                addIfPresent(features, text, " popcnt ", NativeFeature.POPCNT);
            } else {
                addIfPresent(features, text, " asimd ", NativeFeature.NEON);
                addIfPresent(features, text, " aes ", NativeFeature.ARM_AES);
                addIfPresent(features, text, " sha1 ", NativeFeature.ARM_SHA);
                addIfPresent(features, text, " sha2 ", NativeFeature.ARM_SHA);
                addIfPresent(features, text, " crc32 ", NativeFeature.CRC32);
            }
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void addIfPresent(EnumSet<NativeFeature> features, String text, String token, NativeFeature feature) {
        if ((" " + text.replace('\n', ' ') + " ").contains(token)) {
            features.add(feature);
        }
    }

    private static String selectTlsProvider(Set<NativeFeature> features) {
        if (features.contains(NativeFeature.AES) || features.contains(NativeFeature.ARM_AES)) {
            return "jdk-aes-intrinsics";
        }
        return "jdk";
    }

    private static String selectCompressionProvider(Set<NativeFeature> features) {
        if (features.contains(NativeFeature.AVX2) || features.contains(NativeFeature.AVX512F) || features.contains(NativeFeature.NEON)) {
            return "jdk-deflater-native-zlib";
        }
        return "jdk-deflater";
    }
}

package dev.strataproxy.codec.minecraft;

import com.github.luben.zstd.Zstd;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Trains zstd dictionaries from representative Minecraft packet samples.
 */
public final class MinecraftZstdDictionaryTrainer {
    private MinecraftZstdDictionaryTrainer() {
    }

    /**
     * Trains a zstd dictionary from sample payloads.
     *
     * @param samples non-empty sample payloads
     * @param dictionaryBytes requested dictionary size
     * @param compressionLevel zstd compression level used by the trainer
     * @return trained dictionary bytes
     */
    public static byte[] train(List<byte[]> samples, int dictionaryBytes, int compressionLevel) {
        Objects.requireNonNull(samples, "samples");
        if (samples.isEmpty()) {
            throw new IllegalArgumentException("samples must not be empty");
        }
        if (dictionaryBytes <= 0) {
            throw new IllegalArgumentException("dictionaryBytes must be positive");
        }
        var copiedSamples = samples.stream()
                .map(sample -> {
                    if (sample == null || sample.length == 0) {
                        throw new IllegalArgumentException("samples must not contain empty entries");
                    }
                    return Arrays.copyOf(sample, sample.length);
                })
                .toArray(byte[][]::new);
        var dictionary = new byte[dictionaryBytes];
        var written = Zstd.trainFromBuffer(copiedSamples, dictionary, false, compressionLevel);
        if (Zstd.isError(written)) {
            throw new MinecraftCodecException("zstd dictionary training failed: " + Zstd.getErrorName(written));
        }
        return Arrays.copyOf(dictionary, Math.toIntExact(written));
    }
}

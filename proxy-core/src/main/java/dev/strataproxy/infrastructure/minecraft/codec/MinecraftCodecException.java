package dev.strataproxy.infrastructure.minecraft.codec;

/**
 * Runtime exception thrown when Minecraft packet encoding or decoding fails.
 */
public final class MinecraftCodecException extends RuntimeException {
    /**
 * Documents this public API element.
 *
     * @param message failure message
     */
    public MinecraftCodecException(String message) {
        super(message);
    }

    /**
 * Documents this public API element.
 *
     * @param message failure message
     * @param cause underlying cause
     */
    public MinecraftCodecException(String message, Throwable cause) {
        super(message, cause);
    }
}

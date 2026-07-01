package dev.strataproxy.codec.minecraft;

/**
 * Runtime exception thrown when Minecraft packet encoding or decoding fails.
 */
public final class MinecraftCodecException extends RuntimeException {
    /**
     * @param message failure message
     */
    public MinecraftCodecException(String message) {
        super(message);
    }

    /**
     * @param message failure message
     * @param cause underlying cause
     */
    public MinecraftCodecException(String message, Throwable cause) {
        super(message, cause);
    }
}

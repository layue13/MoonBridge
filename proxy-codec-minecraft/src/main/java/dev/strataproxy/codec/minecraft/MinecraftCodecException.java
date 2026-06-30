package dev.strataproxy.codec.minecraft;

public final class MinecraftCodecException extends RuntimeException {
    public MinecraftCodecException(String message) {
        super(message);
    }

    public MinecraftCodecException(String message, Throwable cause) {
        super(message, cause);
    }
}

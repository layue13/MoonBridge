package dev.strataproxy.core.protocol;

/** Invalid or truncated Minecraft protocol data. */
public final class ProtocolException extends RuntimeException {
    public ProtocolException(String message) { super(message); }
    public ProtocolException(String message, Throwable cause) { super(message, cause); }
}

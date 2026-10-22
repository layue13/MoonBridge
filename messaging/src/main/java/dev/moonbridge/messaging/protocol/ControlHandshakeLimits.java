package dev.moonbridge.messaging.protocol;

/**
 * UTF-8 byte limits for the identity fields of a backend control-channel registration. The backend validates its
 * configuration against the same limits the proxy enforces when it reads the frame, so a registration the proxy
 * would reject is refused at backend start-up instead of failing on every reconnect.
 */
public final class ControlHandshakeLimits {
    public static final int INSTANCE_ID_BYTES = 64;
    public static final int BACKEND_NAME_BYTES = 128;
    public static final int GAME_ADDRESS_BYTES = 1024;
    public static final int GENERATION_BYTES = 64;
    public static final int KEY_ID_BYTES = 64;

    private ControlHandshakeLimits() { }
}

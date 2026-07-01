package dev.strataproxy.network;

/**
 * Effective backend forwarding mode and secret used by Minecraft relay handlers.
 *
 * @param mode forwarding mode, such as {@code none}, {@code velocity-modern}, {@code bungee-legacy}, or {@code bungee-guard}
 * @param secret forwarding secret for modes that require one
 */
public record MinecraftForwardingRuntime(String mode, String secret) {
    /**
     * Normalizes blank forwarding modes and null secrets.
     */
    public MinecraftForwardingRuntime {
        mode = mode == null || mode.isBlank() ? "none" : mode.trim();
        secret = secret == null ? "" : secret;
    }

    static MinecraftForwardingRuntime none() {
        return new MinecraftForwardingRuntime("none", "");
    }

    boolean velocityModern() {
        return "velocity-modern".equalsIgnoreCase(mode);
    }

    boolean bungeeLegacy() {
        return "bungee-legacy".equalsIgnoreCase(mode);
    }

    boolean bungeeGuard() {
        return "bungee-guard".equalsIgnoreCase(mode);
    }

    boolean bungeeHandshakeForwarding() {
        return bungeeLegacy() || bungeeGuard();
    }
}

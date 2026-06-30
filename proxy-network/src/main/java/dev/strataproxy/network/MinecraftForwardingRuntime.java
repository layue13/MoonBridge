package dev.strataproxy.network;

public record MinecraftForwardingRuntime(String mode, String secret) {
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

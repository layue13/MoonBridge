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
}

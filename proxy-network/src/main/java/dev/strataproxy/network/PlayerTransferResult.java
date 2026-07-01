package dev.strataproxy.network;

public record PlayerTransferResult(
        boolean success,
        String outcome,
        String player,
        String sourceServer,
        String targetServer) {
    public static PlayerTransferResult success(String player, String sourceServer, String targetServer) {
        return new PlayerTransferResult(true, "success", value(player), value(sourceServer), value(targetServer));
    }

    public static PlayerTransferResult failure(String outcome, String player, String sourceServer, String targetServer) {
        return new PlayerTransferResult(false, value(outcome), value(player), value(sourceServer), value(targetServer));
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}

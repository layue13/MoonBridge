package dev.strataproxy.network;

/**
 * Result of moving a player between backend servers.
 *
 * @param success whether the transfer completed
 * @param outcome stable outcome or failure reason
 * @param player player name
 * @param sourceServer source backend name
 * @param targetServer requested target backend name
 */
public record PlayerTransferResult(
        boolean success,
        String outcome,
        String player,
        String sourceServer,
        String targetServer) {
    /**
     * Creates a successful transfer result.
     *
     * @param player player name
     * @param sourceServer source backend name
     * @param targetServer target backend name
     * @return successful transfer result
     */
    public static PlayerTransferResult success(String player, String sourceServer, String targetServer) {
        return new PlayerTransferResult(true, "success", value(player), value(sourceServer), value(targetServer));
    }

    /**
     * Creates a failed transfer result.
     *
     * @param outcome failure outcome
     * @param player player name
     * @param sourceServer source backend name
     * @param targetServer target backend name
     * @return failed transfer result
     */
    public static PlayerTransferResult failure(String outcome, String player, String sourceServer, String targetServer) {
        return new PlayerTransferResult(false, value(outcome), value(player), value(sourceServer), value(targetServer));
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}

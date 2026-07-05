package dev.strataproxy.plugin.service;

/**
 * Result of a plugin-requested server registry mutation.
 *
 * @param success whether the mutation completed
 * @param outcome stable diagnostic outcome string
 * @param message human-readable diagnostic message
 * @param server current server view when available
 */
public record ServerMutationResult(boolean success, String outcome, String message, ServerView server) {
    /**
     * Creates a successful mutation result.
     *
     * @param outcome stable diagnostic outcome string
     * @param server current server view when available
     * @return successful result
     */
    public static ServerMutationResult success(String outcome, ServerView server) {
        return new ServerMutationResult(true, outcome, "", server);
    }

    /**
     * Creates a failed mutation result.
     *
     * @param outcome stable diagnostic outcome string
     * @param message human-readable diagnostic message
     * @return failed result
     */
    public static ServerMutationResult failure(String outcome, String message) {
        return new ServerMutationResult(false, outcome, message == null ? "" : message, null);
    }
}

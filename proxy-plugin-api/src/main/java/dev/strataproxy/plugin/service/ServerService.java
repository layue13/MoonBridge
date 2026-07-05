package dev.strataproxy.plugin.service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.Collection;
import java.util.Optional;

/**
 * Backend lookup and controlled registry mutation service exposed to plugins.
 */
public interface ServerService {
    /**
 * Provides find.
 *
     * @param serverName backend name
     * @return backend view when registered
     */
    Optional<ServerView> find(String serverName);

    /**
     * Finds the first registered backend with a tag.
     *
     * @param tag tag to match
     * @return matching backend view, if any
     */
    Optional<ServerView> firstWithTag(String tag);

    /**
 * Provides servers.
 *
     * @return snapshot of registered backend views
     */
    Collection<ServerView> servers();

    /**
     * Registers or replaces a backend owned by the current plugin.
     *
     * <p>Implementations may reject attempts to replace static servers or servers owned by another plugin.</p>
     *
     * @param registration backend registration request
     * @return asynchronous mutation result
     */
    default CompletionStage<ServerMutationResult> register(ServerRegistration registration) {
        return CompletableFuture.completedFuture(ServerMutationResult.failure("unsupported", "server mutation is not available"));
    }

    /**
     * Removes a backend owned by the current plugin.
     *
     * @param serverName backend name
     * @param removal removal behavior
     * @return asynchronous mutation result
     */
    default CompletionStage<ServerMutationResult> unregister(String serverName, ServerRemoval removal) {
        return CompletableFuture.completedFuture(ServerMutationResult.failure("unsupported", "server mutation is not available"));
    }

    /**
     * Enables or disables drain mode for a backend owned by the current plugin.
     *
     * @param serverName backend name
     * @param drainMode whether new players should be rejected
     * @param persistence whether the change should be persisted
     * @return asynchronous mutation result
     */
    default CompletionStage<ServerMutationResult> setDrainMode(
            String serverName,
            boolean drainMode,
            ServerPersistence persistence) {
        return CompletableFuture.completedFuture(ServerMutationResult.failure("unsupported", "server mutation is not available"));
    }
}

package dev.strataproxy.plugin.service;

/**
 * Persistence behavior for plugin-requested server registry mutations.
 */
public enum ServerPersistence {
    /**
     * Runtime-only mutation that disappears when the proxy restarts.
     */
    EPHEMERAL,
    /**
     * Mutation written through the proxy registry store.
     */
    PERSISTENT
}

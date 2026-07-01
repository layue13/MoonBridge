package dev.strataproxy.admin;

import dev.strataproxy.api.server.DrainPolicy;
import dev.strataproxy.api.server.RegisteredServer;
import dev.strataproxy.api.server.ServerDescriptor;
import dev.strataproxy.api.server.ServerHealth;
import dev.strataproxy.api.server.ServerLoad;
import dev.strataproxy.api.server.ServerRegistry;

import java.io.IOException;
import java.util.Collection;
import java.util.Optional;

/**
 * Admin mutation facade for the backend registry with persistence rollback on save failure.
 */
public final class AdminRegistryService {
    private final ServerRegistry registry;
    private final RegistryStore store;

    /**
     * @param registry registry to mutate
     * @param store persistence store; no-op persistence is used when {@code null}
     */
    public AdminRegistryService(ServerRegistry registry, RegistryStore store) {
        this.registry = registry;
        this.store = store == null ? NoopRegistryStore.INSTANCE : store;
    }

    /**
     * Registers or replaces a backend and persists the new registry snapshot.
     *
     * @param descriptor backend descriptor
     * @return registered server view
     * @throws IOException when persistence fails
     */
    public RegisteredServer register(ServerDescriptor descriptor) throws IOException {
        var previous = registry.get(descriptor.name()).map(RegisteredServer::descriptor);
        var server = registry.registerOrReplace(descriptor);
        try {
            persist();
        } catch (IOException exception) {
            if (previous.isPresent()) {
                registry.registerOrReplace(previous.get());
            } else {
                registry.unregister(descriptor.name(), DrainPolicy.rejectNew());
            }
            throw exception;
        }
        return server;
    }

    /**
     * Unregisters a backend and persists the registry when changed.
     *
     * @param name backend name
     * @param policy drain policy
     * @return {@code true} when a server was changed
     * @throws IOException when persistence fails
     */
    public boolean unregister(String name, DrainPolicy policy) throws IOException {
        var changed = registry.unregister(name, policy);
        if (changed) {
            persist();
        }
        return changed;
    }

    public void updateHealth(String name, ServerHealth health) {
        registry.updateHealth(name, health);
    }

    public void updateLoad(String name, ServerLoad load) {
        registry.updateLoad(name, load);
    }

    public boolean updateDrainMode(String name, boolean drainMode) throws IOException {
        if (registry.get(name).isEmpty()) {
            return false;
        }
        registry.updateDrainMode(name, drainMode);
        persist();
        return true;
    }

    public Optional<RegisteredServer> get(String name) {
        return registry.get(name);
    }

    public Collection<RegisteredServer> snapshot() {
        return registry.snapshot();
    }

    public void persist() throws IOException {
        store.save(registry.snapshot());
    }
}

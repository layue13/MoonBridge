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

public final class AdminRegistryService {
    private final ServerRegistry registry;
    private final RegistryStore store;

    public AdminRegistryService(ServerRegistry registry, RegistryStore store) {
        this.registry = registry;
        this.store = store == null ? NoopRegistryStore.INSTANCE : store;
    }

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

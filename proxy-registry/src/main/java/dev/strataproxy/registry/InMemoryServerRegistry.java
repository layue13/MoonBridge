package dev.strataproxy.registry;

import dev.strataproxy.api.server.DrainPolicy;
import dev.strataproxy.api.server.RegisteredServer;
import dev.strataproxy.api.server.ServerDescriptor;
import dev.strataproxy.api.server.ServerHealth;
import dev.strataproxy.api.server.ServerLoad;
import dev.strataproxy.api.server.ServerRegistry;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryServerRegistry implements ServerRegistry {
    private final ConcurrentHashMap<String, MutableRegisteredServer> servers = new ConcurrentHashMap<>();

    @Override
    public RegisteredServer register(ServerDescriptor descriptor) {
        var server = new MutableRegisteredServer(descriptor);
        var existing = servers.putIfAbsent(descriptor.name(), server);
        if (existing != null) {
            throw new IllegalArgumentException("server already registered: " + descriptor.name());
        }
        return server;
    }

    @Override
    public RegisteredServer registerOrReplace(ServerDescriptor descriptor) {
        return servers.compute(descriptor.name(), (ignored, existing) -> {
            if (existing == null) {
                return new MutableRegisteredServer(descriptor);
            }
            existing.replaceDescriptor(descriptor);
            return existing;
        });
    }

    @Override
    public boolean unregister(String name, DrainPolicy policy) {
        var server = servers.get(name);
        if (server == null) {
            return false;
        }
        server.updateDrainMode(true);
        if (policy != null && policy.rejectNewConnections() && !policy.migrateExistingPlayers()) {
            return servers.remove(name, server);
        }
        return true;
    }

    @Override
    public Optional<RegisteredServer> get(String name) {
        return Optional.ofNullable(servers.get(name)).map(RegisteredServer.class::cast);
    }

    @Override
    public Collection<RegisteredServer> snapshot() {
        return servers.values().stream().map(RegisteredServer.class::cast).toList();
    }

    @Override
    public void updateHealth(String name, ServerHealth health) {
        var server = requireServer(name);
        server.updateHealth(health);
    }

    @Override
    public void updateLoad(String name, ServerLoad load) {
        var server = requireServer(name);
        server.updateLoad(load);
    }

    @Override
    public void updateDrainMode(String name, boolean drainMode) {
        var server = requireServer(name);
        server.updateDrainMode(drainMode);
    }

    private MutableRegisteredServer requireServer(String name) {
        var server = servers.get(name);
        if (server == null) {
            throw new IllegalArgumentException("unknown server: " + name);
        }
        return server;
    }
}

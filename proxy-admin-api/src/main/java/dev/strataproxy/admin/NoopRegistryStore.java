package dev.strataproxy.admin;

import dev.strataproxy.api.server.RegisteredServer;
import dev.strataproxy.api.server.ServerDescriptor;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

public enum NoopRegistryStore implements RegistryStore {
    INSTANCE;

    @Override
    public List<ServerDescriptor> load() {
        return List.of();
    }

    @Override
    public void save(Collection<RegisteredServer> servers) throws IOException {
    }
}

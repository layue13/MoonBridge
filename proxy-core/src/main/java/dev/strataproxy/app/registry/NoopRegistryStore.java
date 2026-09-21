package dev.strataproxy.app.registry;

import dev.strataproxy.api.server.RegisteredServer;
import dev.strataproxy.api.server.ServerDescriptor;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

/**
 * Registry store that deliberately performs no persistence.
 */
public enum NoopRegistryStore implements RegistryStore {
    /** Singleton no-op store instance. */
    INSTANCE;

    @Override
    /** Provides load. */
    public List<ServerDescriptor> load() {
        return List.of();
    }

    @Override
    /** Provides save. */
    public void save(Collection<RegisteredServer> servers) throws IOException {
    }
}

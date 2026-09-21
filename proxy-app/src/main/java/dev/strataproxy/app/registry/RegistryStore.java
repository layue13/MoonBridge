package dev.strataproxy.app.registry;

import dev.strataproxy.api.server.RegisteredServer;
import dev.strataproxy.api.server.ServerDescriptor;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

/**
 * Persistence boundary for backend registry entries.
 */
public interface RegistryStore {
    /**
     * Loads persisted backend descriptors.
     *
     * @return descriptors to register on startup
     * @throws IOException when persisted data cannot be read
     */
    List<ServerDescriptor> load() throws IOException;

    /**
     * Persists the current registry snapshot.
     *
     * @param servers registered servers to save
     * @throws IOException when data cannot be written
     */
    void save(Collection<RegisteredServer> servers) throws IOException;
}

package dev.strataproxy.admin;

import dev.strataproxy.api.server.RegisteredServer;
import dev.strataproxy.api.server.ServerDescriptor;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

public interface RegistryStore {
    List<ServerDescriptor> load() throws IOException;

    void save(Collection<RegisteredServer> servers) throws IOException;
}

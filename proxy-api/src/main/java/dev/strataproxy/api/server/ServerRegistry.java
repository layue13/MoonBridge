package dev.strataproxy.api.server;

import java.util.Collection;
import java.util.Optional;

public interface ServerRegistry {
    RegisteredServer register(ServerDescriptor descriptor);

    RegisteredServer registerOrReplace(ServerDescriptor descriptor);

    boolean unregister(String name, DrainPolicy policy);

    Optional<RegisteredServer> get(String name);

    Collection<RegisteredServer> snapshot();

    void updateHealth(String name, ServerHealth health);

    void updateLoad(String name, ServerLoad load);

    void updateDrainMode(String name, boolean drainMode);
}

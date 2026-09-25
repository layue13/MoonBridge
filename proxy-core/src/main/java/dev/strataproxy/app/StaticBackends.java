package dev.strataproxy.app;

import dev.strataproxy.core.backend.BackendCatalog;
import dev.strataproxy.core.backend.BackendId;
import dev.strataproxy.core.backend.BackendOwner;
import dev.strataproxy.core.backend.BackendRegistration;

import java.net.URI;
import java.util.Objects;

/** Loads configured servers through the same catalog used by registration plugins. */
public final class StaticBackends {
    private static final BackendOwner OWNER = new BackendOwner("static-config", 0);

    private StaticBackends() {
    }

    public static void register(ProxyConfiguration configuration, BackendCatalog catalog) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(catalog, "catalog");
        for (var backend : configuration.backends()) {
            catalog.register(new BackendRegistration(
                    new BackendId(backend.name()),
                    OWNER,
                    URI.create("tcp://" + backend.address().trim()),
                    backend.tags(),
                    java.util.Map.of()));
        }
    }
}

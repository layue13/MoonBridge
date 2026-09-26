package dev.strataproxy.api;

import dev.strataproxy.api.event.Events;
import java.util.Map;
import org.slf4j.Logger;

/** Services available to a loaded proxy plugin. Retained player and server handles expire after disable. */
public interface PluginContext {
    Players players();

    Servers servers();

    Commands commands();

    Events events();

    /**
     * Returns the service for messaging with independently connected backend instances.
     *
     * @throws UnsupportedOperationException when the hosting proxy does not provide backend channels
     */
    default BackendChannels backendChannels() {
        throw new UnsupportedOperationException("Backend channels are not available");
    }

    Logger logger();

    /** Immutable configuration assigned to this plugin by the proxy. */
    default Map<String, String> settings() {
        return Map.of();
    }
}

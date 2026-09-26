package dev.strataproxy.api;

import org.slf4j.Logger;
import java.util.Map;

/** Services available to a loaded proxy plugin. Retained player and server handles expire after disable. */
public interface PluginContext {
    Players players();

    Servers servers();

    Commands commands();

    Logger logger();

    /** Immutable configuration assigned to this plugin by the proxy. */
    default Map<String, String> settings() {
        return Map.of();
    }
}

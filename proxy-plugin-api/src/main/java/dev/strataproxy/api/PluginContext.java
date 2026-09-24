package dev.strataproxy.api;

import org.slf4j.Logger;
import java.util.Map;

/** Services available to a loaded proxy plugin. */
public interface PluginContext {
    Players players();

    Servers servers();

    Logger logger();

    /** Immutable configuration assigned to this plugin by the proxy. */
    default Map<String, String> settings() {
        return Map.of();
    }
}

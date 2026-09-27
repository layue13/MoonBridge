package dev.moonbridge.api;

import dev.moonbridge.api.event.Events;
import dev.moonbridge.messaging.Messaging;
import java.util.Map;
import org.slf4j.Logger;

/** Services available to a loaded proxy plugin. Retained player and server handles expire after disable. */
public interface PluginContext {
    Players players();

    Servers servers();

    Commands commands();

    Events events();

    /** Returns this plugin's lifecycle-owned message channel scope. */
    Messaging messaging();

    Logger logger();

    /** Immutable configuration assigned to this plugin by the proxy. */
    default Map<String, String> settings() {
        return Map.of();
    }
}

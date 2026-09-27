package dev.moonbridge.api;

import dev.moonbridge.api.event.Events;
import dev.moonbridge.api.permission.Permissions;
import dev.moonbridge.messaging.Messaging;
import java.util.Map;
import java.nio.file.Path;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;

/** Services available to a loaded proxy plugin. Retained player and server handles expire after disable. */
public interface PluginContext {
    /** Random identifier for this proxy process incarnation, shared with forwarded backend sessions. */
    UUID proxyEpoch();

    Players players();

    Servers servers();

    Commands commands();

    Events events();

    /** Returns this plugin's lifecycle-owned message channel scope. */
    Messaging messaging();

    Logger logger();

    /** Player permission queries backed by the proxy's configured provider. */
    Permissions permissions();

    /** Dedicated persistent data directory owned by this plugin. */
    Path dataDirectory();

    /** Adds a dependency JAR from this plugin's data directory to its plugin class loader. */
    void addLibrary(Path jar) throws IOException;

    /** Immutable configuration assigned to this plugin by the proxy. */
    default Map<String, String> settings() {
        return Map.of();
    }
}

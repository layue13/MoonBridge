package dev.strataproxy.bukkit;

import dev.strataproxy.messaging.Messaging;
import org.bukkit.plugin.Plugin;

/** Shared service provided by the StrataProxyBackend Bukkit plugin. */
public interface BukkitMessagingService {
    /**
     * Returns this enabled plugin's owned messaging scope. Repeated lookups for
     * the same plugin return the same scope. Message/request handlers execute on
     * the Bukkit main thread. Never block that thread waiting for a remote reply.
     * The host closes the scope automatically when the owner is disabled.
     */
    Messaging forPlugin(Plugin owner);
}

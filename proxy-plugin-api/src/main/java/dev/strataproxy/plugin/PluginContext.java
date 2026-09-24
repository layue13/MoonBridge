package dev.strataproxy.plugin;

import dev.strataproxy.plugin.command.CommandRegistry;
import dev.strataproxy.plugin.event.EventBus;
import dev.strataproxy.plugin.route.RouteService;
import dev.strataproxy.plugin.service.PlayerService;
import dev.strataproxy.plugin.service.ProxyChannelService;
import dev.strataproxy.plugin.service.Scheduler;
import dev.strataproxy.plugin.service.ServerService;
import org.slf4j.Logger;

/**
 * Services and metadata exposed to a plugin during its lifecycle.
 */
public interface PluginContext {
    /**
 * Provides metadata.
 *
     * @return metadata loaded for the current plugin
     */
    PluginMetadata metadata();

    /**
 * Provides commands.
 *
     * @return command registry used to expose plugin commands
     */
    CommandRegistry commands();

    /**
 * Provides events.
 *
     * @return event bus for subscribing to proxy lifecycle and player events
     */
    EventBus events();

    /**
 * Provides players.
 *
     * @return player lookup and transfer service
     */
    PlayerService players();

    /** Returns the independent backend-agent channel broker. */
    ProxyChannelService channels();

    /** Registers policies for initial connection and in-session transfer routing. */
    RouteService routes();

    /**
 * Provides servers.
 *
     * @return backend server lookup service
     */
    ServerService servers();

    /**
 * Provides scheduler.
 *
     * @return scheduler for asynchronous and repeating plugin tasks
     */
    Scheduler scheduler();

    /**
 * Provides logger.
 *
     * @return plugin-scoped logger
     */
    Logger logger();
}

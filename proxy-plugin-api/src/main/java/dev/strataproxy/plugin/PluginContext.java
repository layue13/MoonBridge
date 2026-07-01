package dev.strataproxy.plugin;

import dev.strataproxy.plugin.command.CommandRegistry;
import dev.strataproxy.plugin.event.EventBus;
import dev.strataproxy.plugin.service.PlayerService;
import dev.strataproxy.plugin.service.Scheduler;
import dev.strataproxy.plugin.service.ServerService;

import java.util.logging.Logger;

/**
 * Services and metadata exposed to a plugin during its lifecycle.
 */
public interface PluginContext {
    /**
     * @return metadata loaded for the current plugin
     */
    PluginMetadata metadata();

    /**
     * @return command registry used to expose plugin commands
     */
    CommandRegistry commands();

    /**
     * @return event bus for subscribing to proxy lifecycle and player events
     */
    EventBus events();

    /**
     * @return player lookup and transfer service
     */
    PlayerService players();

    /**
     * @return backend server lookup service
     */
    ServerService servers();

    /**
     * @return scheduler for asynchronous and repeating plugin tasks
     */
    Scheduler scheduler();

    /**
     * @return plugin-scoped logger
     */
    Logger logger();
}

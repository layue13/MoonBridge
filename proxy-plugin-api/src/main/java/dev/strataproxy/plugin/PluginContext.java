package dev.strataproxy.plugin;

import dev.strataproxy.plugin.command.CommandRegistry;
import dev.strataproxy.plugin.event.EventBus;
import dev.strataproxy.plugin.service.PlayerService;
import dev.strataproxy.plugin.service.Scheduler;
import dev.strataproxy.plugin.service.ServerService;

import java.util.logging.Logger;

public interface PluginContext {
    PluginMetadata metadata();

    CommandRegistry commands();

    EventBus events();

    PlayerService players();

    ServerService servers();

    Scheduler scheduler();

    Logger logger();
}

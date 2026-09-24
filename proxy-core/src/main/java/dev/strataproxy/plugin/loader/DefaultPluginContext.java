package dev.strataproxy.plugin.loader;

import dev.strataproxy.plugin.PluginContext;
import dev.strataproxy.plugin.PluginMetadata;
import dev.strataproxy.plugin.command.CommandRegistry;
import dev.strataproxy.plugin.event.EventBus;
import dev.strataproxy.plugin.service.PlayerService;
import dev.strataproxy.plugin.service.ProxyChannelService;
import dev.strataproxy.plugin.service.Scheduler;
import dev.strataproxy.plugin.service.ServerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DefaultPluginContext implements PluginContext {
    private final PluginMetadata metadata;
    private final CommandRegistry commands;
    private final EventBus events;
    private final PlayerService players;
    private final ProxyChannelService channels;
    private final ServerService servers;
    private final Scheduler scheduler;
    private final Logger logger;

    DefaultPluginContext(
            PluginMetadata metadata,
            CommandRegistry commands,
            EventBus events,
            PlayerService players,
            ProxyChannelService channels,
            ServerService servers,
            Scheduler scheduler) {
        this.metadata = metadata;
        this.commands = commands;
        this.events = events;
        this.players = players;
        this.channels = channels;
        this.servers = servers;
        this.scheduler = scheduler;
        this.logger = LoggerFactory.getLogger("dev.strataproxy.plugin." + metadata.id());
    }

    @Override
    /** Provides metadata. */
    public PluginMetadata metadata() {
        return metadata;
    }

    @Override
    /** Provides commands. */
    public CommandRegistry commands() {
        return commands;
    }

    @Override
    /** Provides events. */
    public EventBus events() {
        return events;
    }

    @Override
    /** Provides players. */
    public PlayerService players() {
        return players;
    }

    @Override
    public ProxyChannelService channels() {
        return channels;
    }

    @Override
    /** Provides servers. */
    public ServerService servers() {
        return servers;
    }

    @Override
    /** Provides scheduler. */
    public Scheduler scheduler() {
        return scheduler;
    }

    @Override
    /** Provides logger. */
    public Logger logger() {
        return logger;
    }
}

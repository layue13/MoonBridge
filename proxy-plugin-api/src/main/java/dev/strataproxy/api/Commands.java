package dev.strataproxy.api;

/** Registers player commands handled by this plugin at the proxy. */
public interface Commands {
    /**
     * Reserves a root command name. Names are case insensitive and unique across plugins.
     * An unregistered command continues to the backend unchanged.
     */
    CommandRegistration register(String name, CommandHandler handler);
}

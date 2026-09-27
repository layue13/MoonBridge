package dev.moonbridge.core.plugin;

/** Indicates the bounded plugin callback executor is full. */
public final class PluginOverloadedException extends RuntimeException {
    public PluginOverloadedException(Throwable cause) {
        super("Plugin callback capacity is exhausted", cause);
    }
}

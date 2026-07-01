package dev.strataproxy.plugin;

public interface ProxyPlugin {
    default void onLoad(PluginContext context) {
    }

    default void onEnable() {
    }

    default void onDisable() {
    }
}

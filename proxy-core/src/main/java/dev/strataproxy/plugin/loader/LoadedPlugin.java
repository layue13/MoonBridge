package dev.strataproxy.plugin.loader;

import dev.strataproxy.plugin.PluginMetadata;
import dev.strataproxy.plugin.ProxyPlugin;

import java.net.URLClassLoader;

record LoadedPlugin(PluginMetadata metadata, ProxyPlugin instance, URLClassLoader classLoader) {
}

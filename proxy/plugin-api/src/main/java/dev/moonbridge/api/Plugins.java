package dev.moonbridge.api;

import java.util.List;
import java.util.Optional;

/** The plugins the proxy loaded with a descriptor, in load order. */
public interface Plugins {
    /** This plugin's own descriptor; empty for a plugin loaded without {@code moonbridge-plugin.yml}. */
    Optional<PluginInfo> self();

    Optional<PluginInfo> find(String id);

    List<PluginInfo> all();
}

package dev.moonbridge.api;

import java.util.Optional;

/**
 * Typed services one plugin offers to the plugins that depend on it. A provider registers an implementation of a type
 * it ships, usually an interface; a plugin that declares the provider in its {@code dependencies} can load that type
 * and look the service up. Lookups only see the plugin's own services and those of its declared dependencies,
 * directly or through other dependencies, so a service can never be reached by accident.
 *
 * <p>Each type has at most one provider. A provider's services are removed when it is disabled; dependents are
 * disabled before their dependencies, so a dependent never observes its provider's services disappearing.
 */
public interface PluginServices {
    /** Offers {@code service} as {@code type}; fails if another plugin already provides {@code type}. */
    <T> ServiceRegistration register(Class<T> type, T service);

    /** The service registered for {@code type} by this plugin or one of its dependencies. */
    <T> Optional<T> find(Class<T> type);

    /** Like {@link #find}, but fails with a message naming the type when no visible plugin provides it. */
    default <T> T require(Class<T> type) {
        return find(type).orElseThrow(() -> new IllegalStateException(
                "No plugin this one depends on provides " + type.getName()));
    }
}

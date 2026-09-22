/**
 * Replaceable runtime adapters.
 *
 * <p>Contains the Minecraft/Netty transport, backend-agent endpoint, registry stores and health
 * probes, plugin host implementations, native capability discovery, and metrics collection.
 * Code outside this package should depend on the public plugin SPI or domain contracts instead of
 * individual adapters.</p>
 */
package dev.strataproxy.infrastructure;

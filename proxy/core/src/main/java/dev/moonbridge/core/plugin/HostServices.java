package dev.moonbridge.core.plugin;

import dev.moonbridge.messaging.internal.LocalMessaging;
import dev.moonbridge.api.PluginInfo;
import dev.moonbridge.api.Players;
import dev.moonbridge.core.backend.BackendCatalog;
import dev.moonbridge.core.event.PermissionService;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** The proxy services a plugin context is built from. */
record HostServices(BackendCatalog catalog, Players players, UUID proxyEpoch, PermissionService permissions,
                    LocalMessaging messaging, CommandService commands, EventRouter events,
                    HostLifecycle lifecycle, PluginServiceRegistry pluginServices,
                    Supplier<List<PluginInfo>> pluginInfos) { }

package dev.moonbridge.core.plugin;

import dev.moonbridge.messaging.internal.LocalMessaging;
import dev.moonbridge.api.Players;
import dev.moonbridge.core.backend.BackendCatalog;
import dev.moonbridge.core.permission.PermissionService;

import java.util.UUID;

/** The proxy services a plugin context is built from. */
record HostServices(BackendCatalog catalog, Players players, UUID proxyEpoch, PermissionService permissions,
                    LocalMessaging messaging, CommandService commands, EventRouter events,
                    HostLifecycle lifecycle) { }

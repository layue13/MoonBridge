package dev.strataproxy.app;

import io.netty.util.NetUtil;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

/** Configuration understood by the new proxy runtime. */
public record ProxyConfiguration(String listen, Authentication authentication, List<Backend> backends,
                                 Plugins plugins, Integer maxConnections, boolean allowOfflinePublicAccess,
                                 Status status) {
    public enum Authentication { OFFLINE, ONLINE_BUNGEE }

    public ProxyConfiguration {
        if (listen == null || listen.isBlank()) {
            throw new IllegalArgumentException("listen is required");
        }
        parseAddress(listen);
        if (authentication == null) {
            throw new IllegalArgumentException("authentication is required: OFFLINE or ONLINE_BUNGEE");
        }
        if (authentication == Authentication.OFFLINE && !allowOfflinePublicAccess
                && !isLiteralLoopback(listen)) {
            throw new IllegalArgumentException("OFFLINE requires a literal loopback listen IP or allowOfflinePublicAccess: true");
        }
        backends = backends == null ? List.of() : List.copyOf(backends);
        plugins = plugins == null ? new Plugins("plugins", Map.of(), null, null) : plugins;
        maxConnections = maxConnections == null ? 4096 : maxConnections;
        if (maxConnections < 1 || maxConnections > 1_000_000) {
            throw new IllegalArgumentException("maxConnections must be between 1 and 1000000");
        }
        status = status == null ? new Status(null, null, null) : status;
        var names = new java.util.HashSet<String>();
        for (var backend : backends) {
            if (!names.add(backend.name())) {
                throw new IllegalArgumentException("duplicate backend: " + backend.name());
            }
        }
    }

    public ProxyConfiguration(String listen, Authentication authentication, List<Backend> backends) {
        this(listen, authentication, backends, null, null, false, null);
    }

    /** User-facing server list values; icon validation needs the config file location and happens in the loader. */
    public record Status(String motd, Integer maxPlayers, String icon) {
        public Status {
            motd = motd == null ? "StrataProxy" : motd;
            if (motd.codePointCount(0, motd.length()) > 1024) {
                throw new IllegalArgumentException("status.motd must contain at most 1024 Unicode code points");
            }
            maxPlayers = maxPlayers == null ? 100 : maxPlayers;
            if (maxPlayers < 1 || maxPlayers > 1_000_000) {
                throw new IllegalArgumentException("status.maxPlayers must be between 1 and 1000000");
            }
            if (icon != null && icon.isBlank()) {
                throw new IllegalArgumentException("status.icon must be a non-empty relative PNG path");
            }
        }
    }

    public InetSocketAddress listenAddress() {
        return parseAddress(listen);
    }

    private static boolean isLiteralLoopback(String listen) {
        String value = listen.trim();
        String host = value.substring(0, value.lastIndexOf(':'));
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        byte[] bytes = NetUtil.createByteArrayFromIpAddressString(host);
        if (bytes == null) return false;
        try {
            return InetAddress.getByAddress(bytes).isLoopbackAddress();
        } catch (java.net.UnknownHostException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public static InetSocketAddress parseAddress(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("address is required");
        }
        var value = text.trim();
        var separator = value.lastIndexOf(':');
        if (separator < 1 || separator == value.length() - 1) {
            throw new IllegalArgumentException("address must be host:port: " + text);
        }
        var host = value.substring(0, separator);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        } else if (host.contains(":")) {
            throw new IllegalArgumentException("IPv6 addresses must be bracketed: " + text);
        }
        int port;
        try {
            port = Integer.parseInt(value.substring(separator + 1));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid port: " + text, exception);
        }
        if (host.isBlank() || port < 0 || port > 65535) {
            throw new IllegalArgumentException("invalid address: " + text);
        }
        return new InetSocketAddress(host, port);
    }

    public record Backend(String name, String address, Map<String, String> tags) {
        public Backend {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("backend name is required");
            }
            name = name.trim();
            if (parseAddress(address).getPort() == 0) {
                throw new IllegalArgumentException("backend port must be positive");
            }
            tags = tags == null ? Map.of() : Map.copyOf(tags);
        }

        public InetSocketAddress socketAddress() {
            return parseAddress(address);
        }
    }

    public record Plugins(String directory, Map<String, Map<String, String>> enabled,
                          Integer initialPlacementTimeoutSeconds, Integer eventTimeoutSeconds) {
        public Plugins {
            if (directory == null || directory.isBlank()) {
                throw new IllegalArgumentException("plugin directory is required");
            }
            initialPlacementTimeoutSeconds = initialPlacementTimeoutSeconds == null
                    ? 15 : initialPlacementTimeoutSeconds;
            if (initialPlacementTimeoutSeconds < 1 || initialPlacementTimeoutSeconds > 120) {
                throw new IllegalArgumentException("initialPlacementTimeoutSeconds must be between 1 and 120");
            }
            eventTimeoutSeconds = eventTimeoutSeconds == null ? 5 : eventTimeoutSeconds;
            if (eventTimeoutSeconds < 1 || eventTimeoutSeconds > 30) {
                throw new IllegalArgumentException("eventTimeoutSeconds must be between 1 and 30");
            }
            enabled = enabled == null ? Map.of() : enabled.entrySet().stream().collect(
                    java.util.stream.Collectors.toUnmodifiableMap(
                            entry -> {
                                if (entry.getKey() == null || entry.getKey().isBlank()) {
                                    throw new IllegalArgumentException("plugin class name is required");
                                }
                                return entry.getKey();
                            },
                            entry -> Map.copyOf(entry.getValue())));
        }
    }
}

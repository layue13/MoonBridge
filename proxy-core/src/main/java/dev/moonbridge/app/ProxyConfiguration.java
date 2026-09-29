package dev.moonbridge.app;

import io.netty.util.NetUtil;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Configuration understood by the new proxy runtime. */
public record ProxyConfiguration(String listen, Authentication authentication, List<Backend> backends,
                                 Plugins plugins, Integer maxConnections, boolean allowOfflinePublicAccess,
                                 Status status, BackendChannel backendChannel, InitialRouting initialRouting) {
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
        plugins = plugins == null ? new Plugins("plugins", Map.of(), null) : plugins;
        initialRouting = initialRouting == null ? new InitialRouting(List.of(), null) : initialRouting;
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
        this(listen, authentication, backends, null, null, false, null, null, null);
    }

    public ProxyConfiguration(String listen, Authentication authentication, List<Backend> backends,
                              Plugins plugins, Integer maxConnections, boolean allowOfflinePublicAccess,
                              Status status) {
        this(listen, authentication, backends, plugins, maxConnections, allowOfflinePublicAccess, status, null, null);
    }

    public ProxyConfiguration(String listen, Authentication authentication, List<Backend> backends,
                              Plugins plugins, Integer maxConnections, boolean allowOfflinePublicAccess,
                              Status status, BackendChannel backendChannel) {
        this(listen, authentication, backends, plugins, maxConnections, allowOfflinePublicAccess, status,
                backendChannel, null);
    }

    /** Explicit default destinations used only when no plugin owns initial placement. */
    public record InitialRouting(List<String> servers, Integer timeoutSeconds) {
        public InitialRouting {
            servers = servers == null ? List.of() : List.copyOf(servers);
            if (servers.size() > 16) {
                throw new IllegalArgumentException("initialRouting.servers must contain at most 16 names");
            }
            var uniqueNames = new java.util.HashSet<String>();
            for (String name : servers) {
                if (name == null || name.isBlank() || !name.equals(name.trim())) {
                    throw new IllegalArgumentException("initialRouting server names must not be blank or have surrounding whitespace");
                }
                if (name.length() > 128) {
                    throw new IllegalArgumentException("initialRouting server names must contain at most 128 characters");
                }
                if (!uniqueNames.add(name)) {
                    throw new IllegalArgumentException("duplicate initialRouting server: " + name);
                }
            }
            timeoutSeconds = timeoutSeconds == null ? 15 : timeoutSeconds;
            if (timeoutSeconds < 1 || timeoutSeconds > 120) {
                throw new IllegalArgumentException("initialRouting.timeoutSeconds must be between 1 and 120");
            }
        }
    }

    /** User-facing server list values; icon validation needs the config file location and happens in the loader. */
    public record Status(String motd, Integer maxPlayers, String icon) {
        public Status {
            motd = motd == null ? "MoonBridge" : motd;
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

    /** Optional listener for backend registration and control messages, independent of player traffic. */
    public record BackendChannel(String listen, Map<String, Client> clients, Integer leaseSeconds,
                                 Integer maxConnections) {
        public BackendChannel {
            if (listen == null || listen.isBlank()) throw new IllegalArgumentException("backendChannel.listen is required");
            parseAddress(listen);
            if (parseAddress(listen).getPort() == 0)
                throw new IllegalArgumentException("backendChannel.listen port must be positive");
            clients = clients == null ? Map.of() : Map.copyOf(clients);
            if (clients.isEmpty()) throw new IllegalArgumentException("backendChannel.clients must not be empty");
            for (String instanceId : clients.keySet()) {
                if (!instanceId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
                    throw new IllegalArgumentException("invalid backendChannel client id: " + instanceId);
            }
            leaseSeconds = leaseSeconds == null ? 30 : leaseSeconds;
            if (leaseSeconds < 5 || leaseSeconds > 3600)
                throw new IllegalArgumentException("backendChannel.leaseSeconds must be between 5 and 3600");
            maxConnections = maxConnections == null ? 128 : maxConnections;
            if (maxConnections < 1 || maxConnections > 4096)
                throw new IllegalArgumentException("backendChannel.maxConnections must be between 1 and 4096");
        }

        public InetSocketAddress listenAddress() { return parseAddress(listen); }
    }

    public record Client(String backendName, String keyId, String secret, Set<String> allowedHosts,
                         Set<String> allowedNamespaces, Set<String> allowedReceiveNamespaces) {
        public Client(String backendName, String keyId, String secret, Set<String> allowedHosts,
                      Set<String> allowedNamespaces) {
            this(backendName, keyId, secret, allowedHosts, allowedNamespaces, allowedNamespaces);
        }

        public Client {
            if (backendName == null || backendName.isBlank())
                throw new IllegalArgumentException("backendChannel client backendName is required");
            if (keyId == null || !keyId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
                throw new IllegalArgumentException("backendChannel client keyId is invalid");
            if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32)
                throw new IllegalArgumentException("backendChannel client secret must contain at least 32 UTF-8 bytes");
            allowedHosts = allowedHosts == null ? Set.of() : Set.copyOf(allowedHosts);
            if (allowedHosts.isEmpty() || allowedHosts.stream().anyMatch(String::isBlank))
                throw new IllegalArgumentException("backendChannel client allowedHosts must not be empty");
            allowedNamespaces = allowedNamespaces == null ? Set.of() : Set.copyOf(allowedNamespaces);
            if (allowedNamespaces.stream()
                    .anyMatch(namespace -> !namespace.matches("[a-z0-9][a-z0-9_.-]{0,63}")))
                throw new IllegalArgumentException("backendChannel client allowedNamespaces is invalid");
            allowedReceiveNamespaces = allowedReceiveNamespaces == null
                    ? allowedNamespaces : Set.copyOf(allowedReceiveNamespaces);
            if (allowedReceiveNamespaces.stream()
                    .anyMatch(namespace -> !namespace.matches("[a-z0-9][a-z0-9_.-]{0,63}")))
                throw new IllegalArgumentException("backendChannel client allowedReceiveNamespaces is invalid");
        }
    }

    public record Plugins(String directory, Map<String, Map<String, String>> enabled,
                          Integer eventTimeoutSeconds, String requiredTransferGuard) {
        public Plugins(String directory, Map<String, Map<String, String>> enabled, Integer eventTimeoutSeconds) {
            this(directory, enabled, eventTimeoutSeconds, null);
        }

        public Plugins {
            if (directory == null || directory.isBlank()) {
                throw new IllegalArgumentException("plugin directory is required");
            }
            eventTimeoutSeconds = eventTimeoutSeconds == null ? 5 : eventTimeoutSeconds;
            if (eventTimeoutSeconds < 1 || eventTimeoutSeconds > 30) {
                throw new IllegalArgumentException("eventTimeoutSeconds must be between 1 and 30");
            }
            if (requiredTransferGuard != null && requiredTransferGuard.isBlank()) {
                throw new IllegalArgumentException("requiredTransferGuard must be a plugin provider class name");
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

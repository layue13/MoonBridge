package dev.strataproxy.plugins.dns;

import dev.strataproxy.api.Plugin;
import dev.strataproxy.api.PluginContext;
import dev.strataproxy.api.ServerDefinition;
import dev.strataproxy.api.ServerRegistration;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.resolver.ResolvedAddressTypes;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.DnsServerAddressStreamProvider;
import io.netty.resolver.dns.NoopDnsCache;
import io.netty.resolver.dns.NoopDnsCnameCache;
import org.slf4j.Logger;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Discovers TCP backends from the A and AAAA records of a configured DNS name. */
public final class DnsDiscoveryPlugin implements Plugin {
    private static final int MAX_CONSECUTIVE_LOOKUP_FAILURES = 3;
    private final AddressResolver resolver;
    private final Object lifecycleLock = new Object();
    private final Map<String, OwnedRegistration> registrations = new LinkedHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean();

    private PluginContext context;
    private Configuration configuration;
    private ScheduledExecutorService scheduler;
    private int consecutiveLookupFailures;
    private volatile boolean closed;

    public DnsDiscoveryPlugin() { this(new UncachedAddressResolver()); }

    DnsDiscoveryPlugin(AddressResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public void onLoad(PluginContext pluginContext) {
        Objects.requireNonNull(pluginContext, "pluginContext");
        synchronized (lifecycleLock) {
            if (context != null) {
                throw new IllegalStateException("DNS discovery plugin is already loaded");
            }
            context = pluginContext;
            configuration = Configuration.from(pluginContext.settings());
        }
    }

    @Override
    public void onEnable() {
        synchronized (lifecycleLock) {
            if (context == null || configuration == null) {
                throw new IllegalStateException("onLoad must complete before onEnable");
            }
            if (closed) {
                throw new IllegalStateException("DNS discovery plugin cannot be restarted after disable");
            }
            if (!started.compareAndSet(false, true)) {
                return;
            }
            scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "strataproxy-dns-discovery");
                thread.setDaemon(true);
                return thread;
            });
            scheduler.scheduleWithFixedDelay(
                    this::refreshSafely,
                    0,
                    configuration.refreshSeconds(),
                    TimeUnit.SECONDS);
        }
    }

    @Override
    public void onDisable() {
        ScheduledExecutorService toStop;
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            toStop = scheduler;
        }

        if (toStop != null) {
            toStop.shutdownNow();
            try {
                if (!toStop.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger().warn("DNS discovery worker did not stop within 5 seconds");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        try {
            resolver.close();
        } catch (Exception failure) {
            logger().warn("Could not close DNS resolver", failure);
        }

        synchronized (lifecycleLock) {
            for (Map.Entry<String, OwnedRegistration> entry : registrations.entrySet()) {
                unregisterQuietly(entry.getKey(), entry.getValue().registration());
            }
            registrations.clear();
        }
    }

    private void refreshSafely() {
        final InetAddress[] resolved;
        try {
            resolved = Objects.requireNonNull(resolver.resolve(configuration.host()), "resolver result");
        } catch (Exception failure) {
            lookupFailed(failure);
            return;
        }

        Map<String, ServerDefinition> desired;
        try {
            desired = definitionsFor(resolved, configuration);
        } catch (RuntimeException invalidResult) {
            lookupFailed(invalidResult);
            return;
        }

        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            consecutiveLookupFailures = 0;
            reconcile(desired);
        }
    }

    private void lookupFailed(Exception failure) {
        synchronized (lifecycleLock) {
            if (closed) return;
            int previousFailures = consecutiveLookupFailures;
            if (consecutiveLookupFailures < MAX_CONSECUTIVE_LOOKUP_FAILURES) consecutiveLookupFailures++;
            if (consecutiveLookupFailures == 1) {
                logger().warn("DNS lookup failed for {}; retaining {} previously discovered backend(s)",
                        configuration.host(), registrations.size(), failure);
            } else if (consecutiveLookupFailures == MAX_CONSECUTIVE_LOOKUP_FAILURES
                    && (previousFailures < MAX_CONSECUTIVE_LOOKUP_FAILURES || !registrations.isEmpty())) {
                if (previousFailures < MAX_CONSECUTIVE_LOOKUP_FAILURES) {
                    logger().warn("DNS lookup for {} has failed {} consecutive times; removing stale backends",
                            configuration.host(), consecutiveLookupFailures);
                }
                reconcile(Map.of());
            }
        }
    }

    private void reconcile(Map<String, ServerDefinition> desired) {
        for (Map.Entry<String, ServerDefinition> entry : desired.entrySet()) {
            String name = entry.getKey();
            ServerDefinition definition = entry.getValue();
            OwnedRegistration existing = registrations.get(name);
            if (existing == null) {
                try {
                    ServerRegistration handle = context.servers().register(definition);
                    registrations.put(name, new OwnedRegistration(definition, handle));
                    logger().info("Discovered backend {} at {}", name, definition.address());
                } catch (RuntimeException failure) {
                    logger().warn("Could not register discovered backend {}", name, failure);
                }
            } else if (!existing.definition().equals(definition)) {
                try {
                    existing.registration().update(definition);
                    registrations.put(name, new OwnedRegistration(definition, existing.registration()));
                    logger().info("Updated discovered backend {} at {}", name, definition.address());
                } catch (RuntimeException failure) {
                    logger().warn("Could not update discovered backend {}; keeping its previous registration", name,
                            failure);
                }
            }
        }

        for (String name : Set.copyOf(registrations.keySet())) {
            if (!desired.containsKey(name)) {
                OwnedRegistration existing = registrations.get(name);
                if (existing != null) {
                    try {
                        existing.registration().unregister();
                        registrations.remove(name);
                        logger().info("Removed DNS backend {}", name);
                    } catch (RuntimeException failure) {
                        logger().warn("Could not remove DNS backend {}; it will be retried", name, failure);
                    }
                }
            }
        }
    }

    private void unregisterQuietly(String name, ServerRegistration registration) {
        try {
            registration.unregister();
            logger().info("Removed DNS backend {} during plugin shutdown", name);
        } catch (RuntimeException failure) {
            logger().warn("Could not remove DNS backend {} during plugin shutdown", name, failure);
        }
    }

    private Logger logger() {
        PluginContext current = context;
        return current == null ? org.slf4j.LoggerFactory.getLogger(DnsDiscoveryPlugin.class) : current.logger();
    }

    private static Map<String, ServerDefinition> definitionsFor(InetAddress[] addresses, Configuration config) {
        Map<String, InetAddress> unique = new LinkedHashMap<>();
        Arrays.stream(addresses)
                .map(address -> Objects.requireNonNull(address, "DNS answer address"))
                .sorted(Comparator.comparingInt((InetAddress address) -> address instanceof Inet4Address ? 0 : 1)
                        .thenComparing(address -> HexFormat.of().formatHex(address.getAddress())))
                .forEach(address -> unique.putIfAbsent(HexFormat.of().formatHex(address.getAddress()), address));

        Map<String, ServerDefinition> result = new LinkedHashMap<>();
        for (Map.Entry<String, InetAddress> entry : unique.entrySet()) {
            String name = config.namePrefix() + entry.getKey();
            URI address;
            try {
                address = new URI("tcp", null, entry.getValue().getHostAddress(), config.port(), null, null, null);
            } catch (URISyntaxException impossibleForValidatedAddress) {
                throw new IllegalArgumentException("DNS answer could not be represented as a TCP URI",
                        impossibleForValidatedAddress);
            }
            ServerDefinition definition = new ServerDefinition(
                    name,
                    address,
                    Map.of("discovery", "dns"),
                    config.capacity(),
                    Map.of("dns.host", config.host()));
            result.put(name, definition);
        }
        return result;
    }

    @FunctionalInterface
    interface AddressResolver extends AutoCloseable {
        InetAddress[] resolve(String host) throws Exception;
        @Override default void close() throws Exception { }
    }

    /** A separate event loop with disabled DNS caches avoids the JVM's process-wide address cache. */
    static final class UncachedAddressResolver implements AddressResolver {
        private final DnsServerAddressStreamProvider nameServers;
        private NioEventLoopGroup eventLoops;
        private DnsNameResolver ipv4;
        private DnsNameResolver ipv6;

        UncachedAddressResolver() { this(null); }

        UncachedAddressResolver(DnsServerAddressStreamProvider nameServers) {
            this.nameServers = nameServers;
        }

        @Override public synchronized InetAddress[] resolve(String host) throws Exception {
            // Netty's localhost hosts-file shortcut returns IPv4 even for an IPv6-only resolver.
            // This reserved local name is not a dynamic DNS record; retain both system loopback addresses.
            if ("localhost".equals(host)) return InetAddress.getAllByName(host);
            if (ipv4 == null) {
                eventLoops = new NioEventLoopGroup(1, task -> {
                    Thread thread = new Thread(task, "strataproxy-dns-query");
                    thread.setDaemon(true);
                    return thread;
                });
                ipv4 = newResolver(ResolvedAddressTypes.IPV4_ONLY);
                ipv6 = newResolver(ResolvedAddressTypes.IPV6_ONLY);
            }
            var ipv4Result = ipv4.resolveAll(host);
            var ipv6Result = ipv6.resolveAll(host);
            var addresses = new ArrayList<InetAddress>();
            Exception firstFailure = null;
            try { addresses.addAll(ipv4Result.get(5, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) {
                ipv4Result.cancel(true);
                ipv6Result.cancel(true);
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            catch (Exception failure) { firstFailure = failure; }
            try { addresses.addAll(ipv6Result.get(5, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) {
                ipv6Result.cancel(true);
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            catch (Exception failure) {
                if (firstFailure == null) firstFailure = failure;
            }
            if (addresses.isEmpty() && firstFailure != null) throw firstFailure;
            return addresses.toArray(InetAddress[]::new);
        }

        private DnsNameResolver newResolver(ResolvedAddressTypes family) {
            var builder = new DnsNameResolverBuilder(eventLoops.next())
                    .datagramChannelType(NioDatagramChannel.class)
                    .queryTimeoutMillis(3000)
                    .resolveCache(NoopDnsCache.INSTANCE)
                    .cnameCache(NoopDnsCnameCache.INSTANCE)
                    .resolvedAddressTypes(family);
            if (nameServers != null) builder.nameServerProvider(nameServers);
            return builder.build();
        }

        @Override public synchronized void close() {
            if (ipv4 != null) ipv4.close();
            if (ipv6 != null) ipv6.close();
            if (eventLoops != null) {
                eventLoops.shutdownGracefully(0, 5, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
            }
        }
    }

    private record OwnedRegistration(ServerDefinition definition, ServerRegistration registration) {
    }

    private record Configuration(String host, int port, String namePrefix, int capacity, int refreshSeconds) {
        private static Configuration from(Map<String, String> settings) {
            Objects.requireNonNull(settings, "settings");
            String host = normalizeHost(required(settings, "host"));
            int port = integer(settings, "port", 25565, 1, 65535);
            int capacity = integer(settings, "capacity", 100, 0, Integer.MAX_VALUE);
            int refreshSeconds = integer(settings, "refreshSeconds", 30, 1, 86400);
            String defaultPrefix = "dns-" + host.replace('.', '-');
            String prefix = settings.getOrDefault("namePrefix", defaultPrefix).trim();
            if (prefix.isEmpty() || !prefix.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
                throw new IllegalArgumentException("setting 'namePrefix' must start with a letter or digit and "
                        + "contain only letters, digits, '.', '_' or '-'");
            }
            return new Configuration(host, port, prefix + "-", capacity, refreshSeconds);
        }

        private static String required(Map<String, String> settings, String key) {
            String value = settings.get(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("missing required plugin setting '" + key + "'");
            }
            return value.trim();
        }

        private static int integer(Map<String, String> settings, String key, int defaultValue, int minimum, int maximum) {
            String value = settings.get(key);
            if (value == null) {
                return defaultValue;
            }
            try {
                int parsed = Integer.parseInt(value.trim());
                if (parsed < minimum || parsed > maximum) {
                    throw new IllegalArgumentException("setting '" + key + "' must be between " + minimum + " and "
                            + maximum);
                }
                return parsed;
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("setting '" + key + "' must be an integer", invalid);
            }
        }

        private static String normalizeHost(String host) {
            if (host.contains(":") || host.endsWith(".")) {
                throw new IllegalArgumentException("setting 'host' must be a DNS hostname without a trailing dot");
            }
            final String ascii;
            try {
                ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("setting 'host' must be a valid DNS hostname", invalid);
            }
            if (ascii.length() > 253 || ascii.isBlank()) {
                throw new IllegalArgumentException("setting 'host' must be a valid DNS hostname");
            }
            return ascii;
        }
    }
}

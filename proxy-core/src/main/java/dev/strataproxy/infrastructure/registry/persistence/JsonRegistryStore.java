package dev.strataproxy.infrastructure.registry.persistence;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.strataproxy.domain.server.ProtocolRange;
import dev.strataproxy.domain.server.RegisteredServer;
import dev.strataproxy.domain.server.ServerCapability;
import dev.strataproxy.domain.server.ServerDescriptor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JSON-file implementation of registry persistence.
 */
public final class JsonRegistryStore implements RegistryStore {
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Path path;

    /**
 * Documents this public API element.
 *
     * @param path JSON registry file path
     */
    public JsonRegistryStore(Path path) {
        this.path = path;
    }

    @Override
    /** Provides load. */
    public List<ServerDescriptor> load() throws IOException {
        if (Files.notExists(path)) {
            return List.of();
        }
        var entries = mapper.readValue(path.toFile(), RegistryEntry[].class);
        return java.util.Arrays.stream(entries).map(RegistryEntry::toDescriptor).toList();
    }

    @Override
    /** Provides save. */
    public void save(java.util.Collection<RegisteredServer> servers) throws IOException {
        var parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        var entries = servers.stream()
                .map(server -> RegistryEntry.from(server.descriptor()))
                .toList();
        var temp = path.resolveSibling(path.getFileName() + ".tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), entries);
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveFailed) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Jackson-bound persisted registry entry.
     */
    public static final class RegistryEntry {
        /**
         * Creates an empty registry entry for JSON binding.
         */
        public RegistryEntry() {
        }

        /** Public field for name. */
        public String name;
        /** Public field for address. */
        public String address;
        /** Public field for tags. */
        public Set<String> tags = Set.of();
        /** Public field for capabilities. */
        public Set<String> capabilities = Set.of();
        /** Public field for min protocol. */
        public int minProtocol;
        /** Public field for max protocol. */
        public int maxProtocol;
        /** Public field for protocol name. */
        public String protocolName = "any";
        /** Public field for weight. */
        public int weight = 100;
        /** Public field for soft capacity. */
        public int softCapacity = 500;
        /** Public field for hard capacity. */
        public int hardCapacity = 600;
        /** Public field for drain mode. */
        public boolean drainMode = false;
        /** Public field for metadata. */
        public Map<String, String> metadata = Map.of();

        static RegistryEntry from(ServerDescriptor descriptor) {
            var entry = new RegistryEntry();
            entry.name = descriptor.name();
            entry.address = descriptor.address().getHostString() + ":" + descriptor.address().getPort();
            entry.tags = descriptor.tags();
            entry.capabilities = descriptor.capabilities().stream().map(Enum::name).collect(Collectors.toUnmodifiableSet());
            entry.minProtocol = descriptor.protocolRange().minProtocol();
            entry.maxProtocol = descriptor.protocolRange().maxProtocol();
            entry.protocolName = descriptor.protocolRange().displayName();
            entry.weight = descriptor.weight();
            entry.softCapacity = descriptor.softCapacity();
            entry.hardCapacity = descriptor.hardCapacity();
            entry.drainMode = descriptor.drainMode();
            entry.metadata = descriptor.metadata();
            return entry;
        }

        ServerDescriptor toDescriptor() {
            return new ServerDescriptor(
                    name,
                    parseAddress(address, 25565),
                    tags,
                    capabilities.stream().map(RegistryEntry::parseCapability).collect(Collectors.toUnmodifiableSet()),
                    new ProtocolRange(minProtocol, maxProtocol, protocolName == null || protocolName.isBlank() ? "any" : protocolName),
                    weight,
                    softCapacity,
                    hardCapacity,
                    drainMode,
                    metadata);
        }

        private static ServerCapability parseCapability(String value) {
            return ServerCapability.valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
        }
    }

    private static InetSocketAddress parseAddress(String value, int defaultPort) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("address must not be blank");
        }
        var trimmed = value.trim();
        var splitAt = trimmed.lastIndexOf(':');
        if (splitAt <= 0) {
            return new InetSocketAddress(trimmed, defaultPort);
        }
        return new InetSocketAddress(trimmed.substring(0, splitAt), Integer.parseInt(trimmed.substring(splitAt + 1)));
    }
}

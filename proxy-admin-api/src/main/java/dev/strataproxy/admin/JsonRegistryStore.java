package dev.strataproxy.admin;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.strataproxy.api.server.ProtocolRange;
import dev.strataproxy.api.server.RegisteredServer;
import dev.strataproxy.api.server.ServerCapability;
import dev.strataproxy.api.server.ServerDescriptor;

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

public final class JsonRegistryStore implements RegistryStore {
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Path path;

    public JsonRegistryStore(Path path) {
        this.path = path;
    }

    @Override
    public List<ServerDescriptor> load() throws IOException {
        if (Files.notExists(path)) {
            return List.of();
        }
        var entries = mapper.readValue(path.toFile(), RegistryEntry[].class);
        return java.util.Arrays.stream(entries).map(RegistryEntry::toDescriptor).toList();
    }

    @Override
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

    public static final class RegistryEntry {
        public String name;
        public String address;
        public Set<String> tags = Set.of();
        public Set<String> capabilities = Set.of();
        public int minProtocol;
        public int maxProtocol;
        public String protocolName = "any";
        public int weight = 100;
        public int softCapacity = 500;
        public int hardCapacity = 600;
        public boolean drainMode = false;
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

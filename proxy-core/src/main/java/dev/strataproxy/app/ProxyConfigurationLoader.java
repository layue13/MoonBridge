package dev.strataproxy.app;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.strataproxy.core.protocol.ServerListStatus;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/** Strict YAML loader; unknown settings are errors during this redesign. */
public final class ProxyConfigurationLoader {
    private static final int MAX_ICON_BYTES = 64 * 1024;
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    public ProxyConfiguration load(Path path) throws IOException {
        return mapper.readValue(path.toFile(), ProxyConfiguration.class);
    }

    /** Loads the immutable protocol value and resolves any icon relative to the YAML file. */
    public ServerListStatus loadServerListStatus(ProxyConfiguration configuration, Path configPath) throws IOException {
        var status = configuration.status();
        String favicon = status.icon() == null ? null : loadFavicon(configPath, status.icon());
        return new ServerListStatus(status.motd(), status.maxPlayers(), favicon);
    }

    private static String loadFavicon(Path configPath, String configuredPath) throws IOException {
        Path iconPath;
        try {
            iconPath = Path.of(configuredPath);
        } catch (RuntimeException invalidPath) {
            throw new IOException("invalid status.icon path: " + configuredPath, invalidPath);
        }
        if (iconPath.isAbsolute()) throw new IOException("status.icon must be relative to the configuration file");
        Path configDirectory = configPath.toAbsolutePath().normalize().getParent();
        if (configDirectory == null) throw new IOException("configuration file has no parent directory");
        Path resolved = configDirectory.resolve(iconPath).normalize();
        Path canonicalIcon = resolved.toRealPath();
        if (!Files.isRegularFile(canonicalIcon) || Files.size(canonicalIcon) > MAX_ICON_BYTES) {
            throw new IOException("status.icon must be a regular PNG file no larger than 64 KiB");
        }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(canonicalIcon)) {
            bytes = input.readNBytes(MAX_ICON_BYTES + 1);
        }
        if (bytes.length > MAX_ICON_BYTES) throw new IOException("status.icon exceeds 64 KiB");
        if (bytes.length < 24 || !startsWith(bytes, PNG_SIGNATURE)
                || bytes[12] != 'I' || bytes[13] != 'H' || bytes[14] != 'D' || bytes[15] != 'R') {
            throw new IOException("status.icon must be a valid 64x64 PNG");
        }
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        int width = header.getInt(16);
        int height = header.getInt(20);
        if (width != 64 || height != 64) throw new IOException("status.icon must be exactly 64x64 pixels");
        var image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null || image.getWidth() != 64 || image.getHeight() != 64) {
            throw new IOException("status.icon must be a decodable 64x64 PNG");
        }
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (value[i] != prefix[i]) return false;
        return true;
    }
}

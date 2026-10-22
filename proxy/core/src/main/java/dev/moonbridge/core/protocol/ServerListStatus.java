package dev.moonbridge.core.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/** Immutable Minecraft 1.7.10 server-list response configuration. */
public final class ServerListStatus {
    private static final int MAX_STATUS_JSON_BYTES = 32_767;
    private static final String JSON_PREFIX = "{\"version\":{\"name\":\"1.7.10\",\"protocol\":5},"
            + "\"players\":{\"max\":";
    private static final String JSON_ONLINE = ",\"online\":";
    private static final String JSON_SUFFIX = ",\"sample\":[]},\"description\":{\"text\":\"";
    private final String motd;
    private final int maxPlayers;
    private final String faviconDataUrl;
    private final String jsonPrefix;
    private final String jsonSuffix;

    /** The application validates the optional icon as a 64x64 PNG before supplying its data URL. */
    public ServerListStatus(String motd, int maxPlayers, String faviconDataUrl) {
        this.motd = Objects.requireNonNull(motd, "motd");
        if (motd.codePointCount(0, motd.length()) > 1024) {
            throw new IllegalArgumentException("motd must contain at most 1024 Unicode code points");
        }
        if (maxPlayers < 1 || maxPlayers > 1_000_000) {
            throw new IllegalArgumentException("maxPlayers must be between 1 and 1000000");
        }
        if (faviconDataUrl != null && !faviconDataUrl.startsWith("data:image/png;base64,")) {
            throw new IllegalArgumentException("favicon must be a PNG data URL");
        }
        if (faviconDataUrl != null) {
            byte[] iconBytes;
            try {
                iconBytes = Base64.getDecoder().decode(faviconDataUrl.substring("data:image/png;base64,".length()));
            } catch (IllegalArgumentException invalidBase64) {
                throw new IllegalArgumentException("favicon must contain valid base64 data", invalidBase64);
            }
            if (iconBytes.length > 64 * 1024) throw new IllegalArgumentException("favicon must not exceed 64 KiB");
        }
        this.maxPlayers = maxPlayers;
        String escapedMotd = escapeJson(motd);
        this.faviconDataUrl = faviconDataUrl;
        this.jsonPrefix = JSON_PREFIX + maxPlayers + JSON_ONLINE;
        this.jsonSuffix = JSON_SUFFIX + escapedMotd + "\"}"
                + (faviconDataUrl == null ? "" : ",\"favicon\":\"" + faviconDataUrl + "\"") + "}";
        String largestResponseJson = jsonPrefix + Integer.MAX_VALUE + jsonSuffix;
        if (largestResponseJson.getBytes(StandardCharsets.UTF_8).length > MAX_STATUS_JSON_BYTES) {
            throw new IllegalArgumentException("status response JSON exceeds the protocol limit of "
                    + MAX_STATUS_JSON_BYTES + " UTF-8 bytes");
        }
    }

    public static ServerListStatus defaultStatus() {
        return new ServerListStatus("MoonBridge", 100, null);
    }

    public String motd() { return motd; }
    public int maxPlayers() { return maxPlayers; }
    public String faviconDataUrl() { return faviconDataUrl; }

    /** Encodes the STATUS response body (packet id and protocol string), without outer frame length. */
    public ByteBuf encode(ByteBufAllocator allocator, int online) {
        Objects.requireNonNull(allocator, "allocator");
        if (online < 0) throw new IllegalArgumentException("online must not be negative");
        String json = jsonPrefix + online + jsonSuffix;
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return ByteBufs.fill(allocator.buffer(1 + ProtocolVarInt.encodedSize(bytes.length) + bytes.length), body -> {
            ProtocolVarInt.write(body, 0);
            ProtocolVarInt.write(body, bytes.length);
            body.writeBytes(bytes);
        });
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            int count = Character.charCount(codePoint);
            if (Character.isSurrogate(value.charAt(offset)) && count == 1) {
                escaped.append(String.format("\\u%04x", (int) value.charAt(offset)));
            } else {
                switch (codePoint) {
                    case '"' -> escaped.append("\\\"");
                    case '\\' -> escaped.append("\\\\");
                    case '\b' -> escaped.append("\\b");
                    case '\f' -> escaped.append("\\f");
                    case '\n' -> escaped.append("\\n");
                    case '\r' -> escaped.append("\\r");
                    case '\t' -> escaped.append("\\t");
                    default -> {
                        if (codePoint < 0x20) escaped.append(String.format("\\u%04x", codePoint));
                        else escaped.appendCodePoint(codePoint);
                    }
                }
            }
            offset += count;
        }
        return escaped.toString();
    }
}

package dev.strataproxy.network;

import java.util.List;
import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * Runtime settings for local Minecraft status-ping responses.
 *
 * @param enabled whether the proxy answers status requests locally
 * @param motd message of the day
 * @param protocolName displayed protocol name
 * @param protocolVersion displayed protocol version
 * @param maxPlayers displayed maximum player count
 * @param favicon optional base64 favicon data URI
 * @param samplePlayers optional player samples
 * @param onlinePlayers supplier for current online player count
 */
public record MinecraftStatusRuntime(
        boolean enabled,
        String motd,
        String protocolName,
        int protocolVersion,
        int maxPlayers,
        String favicon,
        List<SamplePlayer> samplePlayers,
        IntSupplier onlinePlayers) {
    public MinecraftStatusRuntime(
            boolean enabled,
            String motd,
            String protocolName,
            int protocolVersion,
            int maxPlayers,
            IntSupplier onlinePlayers) {
        this(enabled, motd, protocolName, protocolVersion, maxPlayers, "", List.of(), onlinePlayers);
    }

    public MinecraftStatusRuntime {
        motd = motd == null || motd.isBlank() ? "StrataProxy" : motd;
        protocolName = protocolName == null || protocolName.isBlank() ? "StrataProxy" : protocolName;
        maxPlayers = Math.max(0, maxPlayers);
        favicon = favicon == null ? "" : favicon;
        samplePlayers = samplePlayers == null ? List.of() : List.copyOf(samplePlayers);
        onlinePlayers = onlinePlayers == null ? () -> 0 : onlinePlayers;
    }

    /**
     * @return disabled status runtime that reports no local status response
     */
    public static MinecraftStatusRuntime disabled() {
        return new MinecraftStatusRuntime(false, "StrataProxy", "StrataProxy", -1, 0, "", List.of(), () -> 0);
    }

    /**
     * Player sample shown in a Minecraft status response.
     *
     * @param name displayed player name
     * @param id UUID string
     */
    public record SamplePlayer(String name, String id) {
        public SamplePlayer {
            name = name == null ? "" : name;
            id = id == null || id.isBlank() ? "00000000-0000-0000-0000-000000000000" : id;
        }
    }

    String responseJson() {
        var online = Math.max(0, onlinePlayers.getAsInt());
        var builder = new StringBuilder(160 + motd.length() + protocolName.length() + favicon.length() + (samplePlayers.size() * 96));
        builder.append("{")
                .append("\"version\":{\"name\":\"")
                .append(escapeJson(protocolName))
                .append("\",\"protocol\":")
                .append(protocolVersion)
                .append("},\"players\":{\"online\":")
                .append(online)
                .append(",\"max\":")
                .append(maxPlayers);
        if (!samplePlayers.isEmpty()) {
            builder.append(",\"sample\":[");
            for (var index = 0; index < samplePlayers.size(); index++) {
                if (index > 0) {
                    builder.append(",");
                }
                var player = samplePlayers.get(index);
                builder.append("{\"name\":\"")
                        .append(escapeJson(player.name()))
                        .append("\",\"id\":\"")
                        .append(escapeJson(player.id()))
                        .append("\"}");
            }
            builder.append("]");
        }
        builder.append("},\"description\":{\"text\":\"")
                .append(escapeJson(motd))
                .append("\"}");
        if (!favicon.isBlank()) {
            builder.append(",\"favicon\":\"")
                    .append(escapeJson(favicon))
                    .append("\"");
        }
        builder.append("}");
        return builder.toString();
    }

    private static String escapeJson(String value) {
        Objects.requireNonNull(value, "value");
        var builder = new StringBuilder(value.length() + 8);
        for (var index = 0; index < value.length(); index++) {
            var current = value.charAt(index);
            switch (current) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (current < 0x20) {
                        builder.append("\\u").append(String.format("%04x", (int) current));
                    } else {
                        builder.append(current);
                    }
                }
            }
        }
        return builder.toString();
    }
}

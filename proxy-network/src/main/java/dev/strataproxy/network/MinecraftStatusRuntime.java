package dev.strataproxy.network;

import java.util.Objects;
import java.util.function.IntSupplier;

public record MinecraftStatusRuntime(
        boolean enabled,
        String motd,
        String protocolName,
        int protocolVersion,
        int maxPlayers,
        IntSupplier onlinePlayers) {
    public MinecraftStatusRuntime {
        motd = motd == null || motd.isBlank() ? "StrataProxy" : motd;
        protocolName = protocolName == null || protocolName.isBlank() ? "StrataProxy" : protocolName;
        maxPlayers = Math.max(0, maxPlayers);
        onlinePlayers = onlinePlayers == null ? () -> 0 : onlinePlayers;
    }

    public static MinecraftStatusRuntime disabled() {
        return new MinecraftStatusRuntime(false, "StrataProxy", "StrataProxy", -1, 0, () -> 0);
    }

    String responseJson() {
        var online = Math.max(0, onlinePlayers.getAsInt());
        return "{"
                + "\"version\":{\"name\":\"" + escapeJson(protocolName) + "\",\"protocol\":" + protocolVersion + "},"
                + "\"players\":{\"online\":" + online + ",\"max\":" + maxPlayers + "},"
                + "\"description\":{\"text\":\"" + escapeJson(motd) + "\"}"
                + "}";
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

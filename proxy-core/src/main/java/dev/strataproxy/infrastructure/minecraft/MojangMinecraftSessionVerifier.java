package dev.strataproxy.infrastructure.minecraft;

import java.net.SocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;

final class MojangMinecraftSessionVerifier implements MinecraftSessionVerifier {
    private static final URI DEFAULT_HAS_JOINED_ENDPOINT = URI.create("https://sessionserver.mojang.com/session/minecraft/hasJoined");
    private static final Pattern JSON_STRING_FIELD = Pattern.compile("\"%s\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"");
    private static final Pattern PROPERTY_OBJECT = Pattern.compile("\\{\\s*\"name\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"\\s*,\\s*\"value\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"(?:\\s*,\\s*\"signature\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\")?\\s*}");

    private final HttpClient client;
    private final URI endpoint;
    private final Duration timeout;

    MojangMinecraftSessionVerifier(Duration timeout) {
        this(HttpClient.newBuilder()
                .connectTimeout(timeout == null ? Duration.ofSeconds(5) : timeout)
                .build(),
                DEFAULT_HAS_JOINED_ENDPOINT,
                timeout == null ? Duration.ofSeconds(5) : timeout);
    }

    MojangMinecraftSessionVerifier(HttpClient client, URI endpoint, Duration timeout) {
        this.client = client == null ? HttpClient.newHttpClient() : client;
        this.endpoint = endpoint == null ? DEFAULT_HAS_JOINED_ENDPOINT : endpoint;
        this.timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
    }

    @Override
    /** Provides verify. */
    public CompletionStage<SessionVerificationResult> verify(String username, String serverHash, SocketAddress remoteAddress) {
        if (username == null || username.isBlank()) {
            return java.util.concurrent.CompletableFuture.completedFuture(SessionVerificationResult.denied("missing username"));
        }
        if (serverHash == null || serverHash.isBlank()) {
            return java.util.concurrent.CompletableFuture.completedFuture(SessionVerificationResult.denied("missing server hash"));
        }
        var request = HttpRequest.newBuilder(hasJoinedUri(username, serverHash))
                .timeout(timeout)
                .GET()
                .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        var profile = parseProfile(response.body());
                        if (profile != null && profile.name().equalsIgnoreCase(username)) {
                            return SessionVerificationResult.allowed("mojang-hasJoined", profile);
                        }
                    }
                    return SessionVerificationResult.denied("mojang-hasJoined status=" + response.statusCode());
                })
                .exceptionally(exception -> SessionVerificationResult.denied("mojang-hasJoined error: " + exception.getClass().getSimpleName()));
    }

    private URI hasJoinedUri(String username, String serverHash) {
        var query = "username=" + encode(username) + "&serverId=" + encode(serverHash);
        return endpoint.resolve(endpoint.getPath() + "?" + query);
    }

    private static GameProfile parseProfile(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        var id = stringField(body, "id");
        var name = stringField(body, "name");
        if (id.isBlank() || name.isBlank()) {
            return null;
        }
        return new GameProfile(uuidFromMojangId(id), name, properties(body));
    }

    private static List<Property> properties(String body) {
        var properties = new ArrayList<Property>();
        var matcher = PROPERTY_OBJECT.matcher(body);
        while (matcher.find()) {
            properties.add(new Property(
                    unescape(matcher.group(1)),
                    unescape(matcher.group(2)),
                    matcher.group(3) == null ? "" : unescape(matcher.group(3))));
        }
        return List.copyOf(properties);
    }

    private static String stringField(String body, String field) {
        var matcher = Pattern.compile(JSON_STRING_FIELD.pattern().formatted(Pattern.quote(field))).matcher(body);
        return matcher.find() ? unescape(matcher.group(1)) : "";
    }

    private static UUID uuidFromMojangId(String id) {
        var normalized = id.replace("-", "");
        if (normalized.length() != 32) {
            return null;
        }
        return UUID.fromString(normalized.substring(0, 8)
                + "-"
                + normalized.substring(8, 12)
                + "-"
                + normalized.substring(12, 16)
                + "-"
                + normalized.substring(16, 20)
                + "-"
                + normalized.substring(20));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String unescape(String value) {
        return value
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\/", "/")
                .replace("\\b", "\b")
                .replace("\\f", "\f")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }
}

package dev.strataproxy.network;

import java.net.SocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletionStage;

final class MojangMinecraftSessionVerifier implements MinecraftSessionVerifier {
    private static final URI DEFAULT_HAS_JOINED_ENDPOINT = URI.create("https://sessionserver.mojang.com/session/minecraft/hasJoined");

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
                    if (response.statusCode() == 200 && looksLikeProfile(response.body(), username)) {
                        return SessionVerificationResult.allowed("mojang-hasJoined");
                    }
                    return SessionVerificationResult.denied("mojang-hasJoined status=" + response.statusCode());
                })
                .exceptionally(exception -> SessionVerificationResult.denied("mojang-hasJoined error: " + exception.getClass().getSimpleName()));
    }

    private URI hasJoinedUri(String username, String serverHash) {
        var query = "username=" + encode(username) + "&serverId=" + encode(serverHash);
        return endpoint.resolve(endpoint.getPath() + "?" + query);
    }

    private static boolean looksLikeProfile(String body, String username) {
        if (body == null || body.isBlank()) {
            return false;
        }
        return body.contains("\"id\"") && body.contains("\"name\"") && body.contains("\"" + jsonToken(username) + "\"");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String jsonToken(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

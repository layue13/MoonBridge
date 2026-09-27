package dev.moonbridge.core.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Asynchronous client for Mojang's sessionserver hasJoined endpoint. */
public final class MojangSessionVerifier implements SessionVerifier {
    public static final URI DEFAULT_ENDPOINT = URI.create("https://sessionserver.mojang.com/session/minecraft/hasJoined");
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final int MAX_PENDING_VERIFICATIONS = 256;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client;
    private final URI endpoint;
    private final Duration timeout;
    private final Executor parseExecutor;
    private final Semaphore pendingVerifications;

    public MojangSessionVerifier(Duration timeout, Executor parseExecutor) {
        this(HttpClient.newBuilder().connectTimeout(timeout).build(), DEFAULT_ENDPOINT, timeout, parseExecutor);
    }

    /** Injectable endpoint and client support private session services and deterministic tests. */
    public MojangSessionVerifier(HttpClient client, URI endpoint, Duration timeout, Executor parseExecutor) {
        this(client, endpoint, timeout, parseExecutor, MAX_PENDING_VERIFICATIONS);
    }

    MojangSessionVerifier(HttpClient client, URI endpoint, Duration timeout, Executor parseExecutor,
                          int maxPendingVerifications) {
        if (client == null || endpoint == null || timeout == null || timeout.isZero() || timeout.isNegative()
                || parseExecutor == null || maxPendingVerifications < 1) {
            throw new IllegalArgumentException(
                    "client, endpoint, positive timeout, parseExecutor, and positive request limit are required");
        }
        if (!"http".equalsIgnoreCase(endpoint.getScheme()) && !"https".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalArgumentException("session endpoint must use HTTP or HTTPS");
        }
        if (endpoint.getRawQuery() != null || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException("session endpoint must not contain a query or fragment");
        }
        this.client = client;
        this.endpoint = endpoint;
        this.timeout = timeout;
        this.parseExecutor = parseExecutor;
        this.pendingVerifications = new Semaphore(maxPendingVerifications);
    }

    @Override
    public CompletionStage<Optional<VerifiedProfile>> verify(String username, String serverHash, String clientIp) {
        if (username == null || username.isBlank() || username.length() > 16 || serverHash == null || serverHash.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("username and serverHash are required"));
        }
        StringBuilder query = new StringBuilder("?username=").append(encode(username))
                .append("&serverId=").append(encode(serverHash));
        if (clientIp != null && !clientIp.isBlank()) query.append("&ip=").append(encode(clientIp));
        final URI uri;
        try {
            uri = URI.create(endpoint.toASCIIString() + query);
        } catch (IllegalArgumentException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).GET()
                .header("Accept", "application/json").build();
        if (!pendingVerifications.tryAcquire()) {
            return CompletableFuture.failedFuture(new RejectedExecutionException("too many pending session verifications"));
        }
        try {
            CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request,
                    HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), MAX_RESPONSE_BYTES));
            CompletableFuture<HttpResponse<byte[]>> deadline = pending.copy()
                    .orTimeout(timeout.toNanos(), TimeUnit.NANOSECONDS);
            deadline.whenComplete((ignored, failure) -> {
                if (failure instanceof TimeoutException) pending.cancel(true);
            });
            CompletableFuture<Optional<VerifiedProfile>> parsed = deadline.thenApplyAsync(
                    response -> parseResponse(username, response), parseExecutor);
            CompletableFuture<Optional<VerifiedProfile>> result = new CompletableFuture<>();
            parsed.whenComplete((profile, failure) -> {
                pendingVerifications.release();
                if (failure == null) result.complete(profile);
                else result.completeExceptionally(failure);
            });
            result.whenComplete((ignored, failure) -> {
                if (result.isCancelled()) pending.cancel(true);
            });
            return result;
        } catch (RuntimeException | Error failure) {
            pendingVerifications.release();
            throw failure;
        }
    }

    private static Optional<VerifiedProfile> parseResponse(String requestedName, HttpResponse<byte[]> response) {
        int status = response.statusCode();
        if (status == 204 || status == 404) return Optional.empty();
        if (status != 200) {
            throw new IllegalStateException("session server returned HTTP " + status);
        }
        try {
            JsonNode profile = JSON.readTree(response.body());
            if (profile == null || !profile.isObject()) throw new IllegalStateException("session server returned invalid profile JSON");
            JsonNode id = profile.get("id");
            JsonNode name = profile.get("name");
            if (id == null || !id.isTextual() || name == null || !name.isTextual()) {
                throw new IllegalStateException("session profile is missing id or name");
            }
            String returnedName = name.textValue();
            if (!returnedName.equalsIgnoreCase(requestedName)) return Optional.empty();
            return Optional.of(new VerifiedProfile(parseUuid(id.textValue()), returnedName, parseProperties(profile.get("properties"))));
        } catch (IOException failure) {
            throw new IllegalStateException("could not read session server response", failure);
        }
    }

    private static List<ProfileProperty> parseProperties(JsonNode properties) {
        if (properties == null) return List.of();
        if (!properties.isArray() || properties.size() > 128) {
            throw new IllegalStateException("session profile properties must be an array of at most 128 entries");
        }
        List<ProfileProperty> result = new ArrayList<>(properties.size());
        for (JsonNode property : properties) {
            if (!property.isObject()) throw new IllegalStateException("session profile property must be an object");
            JsonNode name = property.get("name");
            JsonNode value = property.get("value");
            JsonNode signature = property.get("signature");
            if (name == null || !name.isTextual() || value == null || !value.isTextual()
                    || (signature != null && !signature.isNull() && !signature.isTextual())) {
                throw new IllegalStateException("session profile property has invalid fields");
            }
            try {
                result.add(new ProfileProperty(name.textValue(), value.textValue(),
                        signature == null || signature.isNull() ? null : signature.textValue()));
            } catch (IllegalArgumentException failure) {
                throw new IllegalStateException("session profile property exceeds field limits", failure);
            }
        }
        return List.copyOf(result);
    }

    private static UUID parseUuid(String value) {
        try {
            if (value.length() == 32) {
                if (!value.matches("[0-9a-fA-F]{32}")) throw new IllegalArgumentException("UUID must be hexadecimal");
                value = value.substring(0, 8) + "-" + value.substring(8, 12) + "-" + value.substring(12, 16)
                        + "-" + value.substring(16, 20) + "-" + value.substring(20);
            } else if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
                throw new IllegalArgumentException("UUID has invalid format");
            }
            return UUID.fromString(value);
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("session profile has invalid UUID", failure);
        }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

}

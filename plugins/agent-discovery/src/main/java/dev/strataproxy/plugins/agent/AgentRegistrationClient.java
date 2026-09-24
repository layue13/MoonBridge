package dev.strataproxy.plugins.agent;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Minimal Java client; use a fresh generation UUID for each agent process start. */
public final class AgentRegistrationClient {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final URI endpoint;
    private final String agentId;
    private final UUID generation;
    private final byte[] secret;
    private final String backendName;
    private final URI backendAddress;
    private final int capacity;
    private final int leaseSeconds;

    public AgentRegistrationClient(URI endpoint, String agentId, UUID generation, byte[] secret,
                                   String backendName, URI backendAddress, int capacity, int leaseSeconds) {
        this.endpoint = endpoint;
        this.agentId = agentId;
        this.generation = generation;
        this.secret = secret.clone();
        this.backendName = backendName;
        this.backendAddress = backendAddress;
        this.capacity = capacity;
        this.leaseSeconds = leaseSeconds;
    }

    public static UUID newGeneration() { return UUID.randomUUID(); }

    public void registerOrHeartbeat() throws IOException, InterruptedException {
        String body = "action=register&generation=" + encode(generation.toString())
                + "&name=" + encode(backendName) + "&address=" + encode(backendAddress.toString())
                + "&capacity=" + capacity + "&leaseSeconds=" + leaseSeconds;
        send(body);
    }

    public void unregister() throws IOException, InterruptedException {
        send("action=unregister&generation=" + encode(generation.toString()));
    }

    private void send(String body) throws IOException, InterruptedException {
        String timestamp = Long.toString(System.currentTimeMillis() / 1000);
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        String nonce = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String signature = sign(secret, agentId, timestamp, nonce, body);
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                .header("X-Agent-Id", agentId).header("X-Timestamp", timestamp)
                .header("X-Nonce", nonce).header("X-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IOException("Registration endpoint returned HTTP " + response.statusCode());
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private static String sign(byte[] secret, String agentId, String timestamp, String nonce, String body) {
        String canonical = "POST\n" + AgentDiscoveryPlugin.PATH + "\n" + timestamp + "\n" + nonce + "\n" + agentId + "\n" + body;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }
}

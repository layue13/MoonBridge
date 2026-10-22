package dev.moonbridge.smoke;

import dev.moonbridge.api.Plugin;
import dev.moonbridge.api.PluginContext;
import dev.moonbridge.api.ServerView;
import dev.moonbridge.messaging.Endpoint;
import dev.moonbridge.messaging.Message;
import dev.moonbridge.messaging.MessageKind;
import dev.moonbridge.messaging.MessageChannel;
import dev.moonbridge.messaging.Messaging;
import dev.moonbridge.messaging.MessagingException;
import dev.moonbridge.messaging.PublishResult;
import dev.moonbridge.messaging.SendResult;
import dev.moonbridge.messaging.Subscription;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** External proxy plugin driven by the isolated real-Uranium acceptance orchestrator. */
public final class ProxyBackendAcceptancePlugin implements Plugin {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);
    private PluginContext context;
    private Messaging messaging;
    private Path controlDir;
    private int aPort;
    private int bPort;
    private volatile boolean stopping;
    private Thread worker;
    private final AtomicInteger eventCount = new AtomicInteger();
    private Subscription rpcSubscription;
    private Subscription eventSubscription;

    @Override public void onLoad(PluginContext loadedContext) {
        context = loadedContext;
        Map<String, String> settings = loadedContext.settings();
        controlDir = Paths.get(required(settings, "controlDir")).toAbsolutePath().normalize();
        aPort = parsePort(settings, "aPort");
        bPort = parsePort(settings, "bPort");
        messaging = loadedContext.messaging();
    }

    @Override public void onEnable() {
        MessageChannel rpc = messaging.channel("accept:rpc");
        rpcSubscription = rpc.onRequest(request -> {
            assertEnvelope(request, "proxy request");
            String payload = text(request);
            if (!payload.equals("proxy")) {
                return java.util.concurrent.CompletableFuture.completedFuture(bytes("unexpected=" + payload));
            }
            return java.util.concurrent.CompletableFuture.completedFuture(bytes(
                    "proxy-ok;main=false;events=" + eventCount.get() + ";id=" + request.id()
                            + ";replyTo=none;source=" + request.source()));
        });
        eventSubscription = messaging.channel("accept:events").subscribe(message -> {
            assertEnvelope(message, "proxy event");
            eventCount.incrementAndGet();
        });
        worker = Thread.ofVirtual().name("backend-acceptance-control").start(this::watchControlFile);
        context.logger().info("Backend acceptance plugin ready; control directory configured");
    }

    private void watchControlFile() {
        Path commandFile = controlDir.resolve("command.txt");
        String lastId = "";
        while (!stopping) {
            try {
                if (Files.isRegularFile(commandFile)) {
                    String[] parts = Files.readString(commandFile, StandardCharsets.UTF_8).trim().split("\\s+", 2);
                    if (parts.length == 2 && !parts[0].equals(lastId)) {
                        lastId = parts[0];
                        try {
                            String result = runPhase(parts[1]);
                            writeResult(lastId, true, result);
                        } catch (Throwable failure) {
                            context.logger().error("Backend acceptance phase failed: " + parts[1], failure);
                            writeResult(lastId, false, parts[1] + ": " + failure.getClass().getSimpleName()
                                    + ": " + safeMessage(failure));
                        }
                    }
                }
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                if (!stopping) Thread.currentThread().interrupt();
                return;
            } catch (IOException failure) {
                context.logger().error("Cannot read acceptance control file", failure);
                return;
            }
        }
    }

    private String runPhase(String phase) throws Exception {
        switch (phase) {
            case "baseline":
            case "reconnect":
                return baseline(phase);
            case "disable":
                return disablePrimaryA();
            case "b-absent-fast":
                return absentB(5, phase);
            case "b-absent":
                return absentB(45, phase);
            case "b-present":
                return presentB();
            default:
                throw new IllegalArgumentException("unknown acceptance phase " + phase);
        }
    }

    private String baseline(String phase) throws Exception {
        waitForBackend("a", aPort, 45);
        waitForBackend("b", bPort, 45);
        String a = request(Endpoint.backend("a"), "accept:rpc", "echo");
        requireContains(a, "node=a;role=primary;main=true", "backend a primary response");
        String b = request(Endpoint.backend("b"), "accept:rpc", "echo");
        requireContains(b, "node=b;role=primary;main=true", "backend b primary response");
        String cross = request(Endpoint.backend("a"), "accept:rpc", "cross");
        requireContains(cross, "cross=node=b;role=primary;main=true", "backend-to-backend request");
        requireContains(cross, ";correlation=valid", "backend request correlation");
        String proxyReply = request(Endpoint.backend("a"), "accept:rpc", "proxy");
        requireContains(proxyReply, "proxy=proxy-ok", "backend-to-proxy request");
        requireContains(proxyReply, ";correlation=valid", "proxy request correlation");
        PublishResult published = messaging.channel("accept:events").publish(bytes("acceptance-event-" + phase))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        requireAccepted(published, Endpoint.proxy(), Endpoint.backend("a"), Endpoint.backend("b"));
        waitForEvents(phase, published.messageId().toString(), 10);
        String aObserver = request(Endpoint.backend("a"), "accept:observer", "stats");
        String bObserver = request(Endpoint.backend("b"), "accept:observer", "stats");
        String aPrimary = request(Endpoint.backend("a"), "accept:rpc", "stats");
        String bPrimary = request(Endpoint.backend("b"), "accept:rpc", "stats");
        requireContains(aObserver, "node=a;role=observer;main=true;events=", "backend a observer");
        requirePositiveEvents(aObserver, "backend a observer");
        requireContains(bObserver, "node=b;role=observer;main=true;events=", "backend b observer");
        requirePositiveEvents(bObserver, "backend b observer");
        requireSameEvent(aObserver, published.messageId().toString());
        requireSameEvent(bObserver, published.messageId().toString());
        requireSameEvent(aPrimary, published.messageId().toString());
        requireSameEvent(bPrimary, published.messageId().toString());
        if (phase.equals("baseline")) {
            writeBaselineEventCounts(aPrimary, aObserver, bPrimary, bObserver);
        } else {
            requireCountsAdvanced(aPrimary, aObserver, bPrimary, bObserver);
        }
        return "BACKEND_PHASE_PASS phase=" + phase + " aPort=" + aPort + " bPort=" + bPort
                + " eventId=" + published.messageId() + " proxyEvents=" + eventCount.get();
    }

    private String disablePrimaryA() throws Exception {
        String response = request(Endpoint.backend("a"), "accept:rpc", "disable");
        requireContains(response, "disable-scheduled;node=a;role=primary", "disable acknowledgement");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) {
            requireBackend("a", aPort);
            try {
                String observer = request(Endpoint.backend("a"), "accept:observer", "stats");
                requireContains(observer, "node=a;role=observer;main=true", "surviving observer");
                requirePositiveEvents(observer, "surviving observer");
                if (primaryRequestFails()) {
                    return "BACKEND_PHASE_PASS phase=disable backendARegistered=true observer=responsive primary=NO_HANDLER";
                }
            } catch (java.util.concurrent.ExecutionException expectedDuringDisable) {
                // Owner disable is asynchronous; retry the observer and primary probes below.
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("backend a primary handler remained registered after disable deadline");
    }

    private boolean primaryRequestFails() throws Exception {
        try {
            request(Endpoint.backend("a"), "accept:rpc", "echo");
            return false;
        } catch (java.util.concurrent.ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof MessagingException
                    && ((MessagingException) cause).code() == MessagingException.Code.NO_HANDLER) return true;
            throw failure;
        }
    }

    private String absentB(int seconds, String phase) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline && context.servers().find("b").isPresent()) Thread.sleep(100);
        if (context.servers().find("b").isPresent()) {
            throw new IllegalStateException("backend b remained registered after " + seconds + " seconds");
        }
        String observer = request(Endpoint.backend("a"), "accept:observer", "stats");
        requireContains(observer, "node=a;role=observer;main=true", "backend a observer during " + phase);
        requirePositiveEvents(observer, "backend a observer during " + phase);
        return "BACKEND_PHASE_PASS phase=" + phase + " backendBAbsent=true backendAObserver=responsive";
    }

    private String presentB() throws Exception {
        waitForBackend("b", bPort, 45);
        PublishResult published = messaging.channel("accept:events").publish(bytes("acceptance-event-b-present"))
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        requireAccepted(published, Endpoint.proxy(), Endpoint.backend("a"), Endpoint.backend("b"));
        waitForBackendEventB(published.messageId().toString(), 10);
        String primary = request(Endpoint.backend("b"), "accept:rpc", "echo");
        requireContains(primary, "node=b;role=primary;main=true", "reconnected backend b primary");
        String observer = request(Endpoint.backend("b"), "accept:observer", "stats");
        requireContains(observer, "node=b;role=observer;main=true", "reconnected backend b observer");
        requirePositiveEvents(observer, "reconnected backend b observer");
        requireSameEvent(primary, published.messageId().toString());
        requireSameEvent(observer, published.messageId().toString());
        return "BACKEND_PHASE_PASS phase=b-present backendBRegistered=true primary=responsive observer=responsive";
    }

    private String request(Endpoint target, String channel, String payload) throws Exception {
        Message reply = messaging.channel(channel).request(target, bytes(payload), REQUEST_TIMEOUT)
                .toCompletableFuture().get(10, TimeUnit.SECONDS);
        if (reply.kind() != MessageKind.REPLY || reply.replyTo() == null || reply.id().equals(reply.replyTo())
                || !target.equals(reply.source()) || !Endpoint.proxy().equals(reply.target())
                || !channel.equals(reply.channel())) {
            throw new IllegalStateException("request reply did not carry replyTo correlation");
        }
        String result = text(reply);
        String requestId = field(result, "originRequestId");
        if (requestId.isEmpty()) requestId = field(result, "id");
        if (!reply.replyTo().toString().equals(requestId)) {
            throw new IllegalStateException("replyTo did not match the originating request id: " + result);
        }
        return result;
    }

    private void waitForBackend(String name, int port, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (context.servers().find(name).isPresent()) {
                requireBackend(name, port);
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("backend " + name + " did not register within " + seconds + " seconds");
    }

    private void requireBackend(String name, int port) {
        ServerView backend = context.servers().find(name)
                .orElseThrow(() -> new IllegalStateException("backend " + name + " is not registered"));
        URI address = backend.address();
        if (!"tcp".equalsIgnoreCase(address.getScheme()) || !"127.0.0.1".equals(address.getHost())
                || address.getPort() != port) {
            throw new IllegalStateException("backend " + name + " has unexpected gameAddress " + address);
        }
    }

    private void waitForEvents(String phase, String expectedEventId, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            String a = request(Endpoint.backend("a"), "accept:rpc", "stats");
            String b = request(Endpoint.backend("b"), "accept:rpc", "stats");
            if (eventCount.get() > 0 && hasPositiveEvents(a) && hasPositiveEvents(b)
                    && expectedEventId.equals(lastEventId(a)) && expectedEventId.equals(lastEventId(b))) return;
            Thread.sleep(100);
        }
        throw new IllegalStateException("event subscribers did not observe the published event during " + phase);
    }

    private void waitForBackendEventB(String expectedEventId, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            String primary = request(Endpoint.backend("b"), "accept:rpc", "stats");
            String observer = request(Endpoint.backend("b"), "accept:observer", "stats");
            if (expectedEventId.equals(lastEventId(primary)) && expectedEventId.equals(lastEventId(observer))) return;
            Thread.sleep(100);
        }
        throw new IllegalStateException("backend b subscribers did not observe the b-present event");
    }

    private void writeBaselineEventCounts(String aPrimary, String aObserver, String bPrimary, String bObserver)
            throws IOException {
        String baseline = eventCount(aPrimary) + " " + eventCount(aObserver) + " "
                + eventCount(bPrimary) + " " + eventCount(bObserver) + System.lineSeparator();
        Files.writeString(controlDir.resolve("baseline-events.txt"), baseline, StandardCharsets.UTF_8);
    }

    private void requireCountsAdvanced(String aPrimary, String aObserver, String bPrimary, String bObserver)
            throws IOException {
        Path saved = controlDir.resolve("baseline-events.txt");
        if (!Files.isRegularFile(saved)) throw new IllegalStateException("baseline event counts are missing");
        String[] before = Files.readString(saved, StandardCharsets.UTF_8).trim().split("\\s+");
        String[] now = { eventCount(aPrimary), eventCount(aObserver), eventCount(bPrimary), eventCount(bObserver) };
        if (before.length != now.length) throw new IllegalStateException("baseline event count file is invalid");
        for (int i = 0; i < now.length; i++) {
            if (Integer.parseInt(now[i]) <= Integer.parseInt(before[i])) {
                throw new IllegalStateException("event subscriber " + i + " did not receive the reconnect broadcast");
            }
        }
    }

    private static String eventCount(String summary) {
        int start = summary.indexOf(";events=");
        if (start < 0) throw new IllegalStateException("event count missing from response: " + summary);
        start += ";events=".length();
        int end = summary.indexOf(';', start);
        return summary.substring(start, end < 0 ? summary.length() : end);
    }

    private static String lastEventId(String summary) {
        int start = summary.indexOf(";lastEvent=");
        if (start < 0) return "";
        start += ";lastEvent=".length();
        int end = summary.indexOf(';', start);
        return summary.substring(start, end < 0 ? summary.length() : end);
    }

    private static String field(String text, String name) {
        String marker = ";" + name + "=";
        int start = text.indexOf(marker);
        if (start < 0) return "";
        start += marker.length();
        int end = text.indexOf(';', start);
        return text.substring(start, end < 0 ? text.length() : end);
    }

    private static void requireSameEvent(String summary, String expectedId) {
        if (!expectedId.equals(lastEventId(summary))) {
            throw new IllegalStateException("subscriber saw a different event id: " + summary);
        }
    }

    private void requireAccepted(PublishResult result, Endpoint... endpoints) {
        for (Endpoint endpoint : endpoints) {
            if (result.results().get(endpoint) != SendResult.ACCEPTED) {
                throw new IllegalStateException("event was not accepted by " + endpoint + ": "
                        + result.results().get(endpoint));
            }
        }
    }

    private static boolean hasPositiveEvents(String summary) {
        int start = summary.indexOf(";events=");
        if (start < 0) return false;
        start += ";events=".length();
        int end = summary.indexOf(';', start);
        try { return Integer.parseInt(summary.substring(start, end < 0 ? summary.length() : end)) > 0; }
        catch (NumberFormatException invalid) { return false; }
    }

    private void requirePositiveEvents(String summary, String description) {
        if (!hasPositiveEvents(summary)) throw new IllegalStateException(description + " has no event count");
    }

    private static void requireContains(String value, String expected, String description) {
        if (!value.contains(expected)) throw new IllegalStateException(description + " failed: " + value);
    }

    private void assertEnvelope(Message message, String description) {
        if (message == null || message.id() == null || message.kind() == null || message.replyTo() != null) {
            throw new IllegalStateException(description + " has invalid message envelope");
        }
    }

    private static String text(Message message) { return new String(message.payload(), StandardCharsets.UTF_8); }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static String required(Map<String, String> settings, String name) {
        String value = settings.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing setting " + name);
        return value;
    }

    private static int parsePort(Map<String, String> settings, String name) {
        int port = Integer.parseInt(required(settings, name));
        if (port < 1 || port > 65535) throw new IllegalArgumentException(name + " is outside the TCP port range");
        return port;
    }

    private void writeResult(String id, boolean success, String content) {
        Path target = controlDir.resolve(id + (success ? ".pass" : ".fail"));
        Path temporary = controlDir.resolve(id + ".result.tmp");
        try {
            Files.createDirectories(controlDir);
            Files.writeString(temporary, content + System.lineSeparator(), StandardCharsets.UTF_8);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            context.logger().error("Could not publish acceptance phase result", failure);
        }
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null ? "no detail" : message.replace('\n', ' ').replace('\r', ' ');
    }

    @Override public void onDisable() {
        stopping = true;
        if (worker != null) {
            worker.interrupt();
            try { worker.join(2000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        if (rpcSubscription != null) rpcSubscription.close();
        if (eventSubscription != null) eventSubscription.close();
    }
}

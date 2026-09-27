package dev.strataproxy.smoke;

import dev.strataproxy.bukkit.BukkitMessagingService;
import dev.strataproxy.messaging.Endpoint;
import dev.strataproxy.messaging.Message;
import dev.strataproxy.messaging.MessageChannel;
import dev.strataproxy.messaging.MessageKind;
import dev.strataproxy.messaging.Messaging;
import dev.strataproxy.messaging.Subscription;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

/** Installed only by the real-Uranium backend acceptance smoke. */
public class BackendAcceptancePlugin extends JavaPlugin {
    private final AtomicInteger eventCount = new AtomicInteger();
    private final AtomicInteger requestCount = new AtomicInteger();
    private volatile String lastEventId = "none";
    private volatile boolean stopping;
    private boolean observer;
    private String backendName;
    private Messaging messaging;
    private Subscription requestSubscription;
    private Subscription eventSubscription;

    @Override public void onEnable() {
        observer = getName().toLowerCase(Locale.ROOT).contains("observer");
        backendName = getConfig().getString("backendName", "").trim();
        if (backendName.isEmpty()) {
            fail("missing backendName configuration");
            return;
        }
        BukkitMessagingService service = Bukkit.getServicesManager().load(BukkitMessagingService.class);
        if (service == null) {
            fail("BukkitMessagingService is unavailable");
            return;
        }
        try {
            // The host supplies the owner-bound scope and dispatches both callbacks on the server thread.
            messaging = service.forPlugin(this);
            String requestChannel = observer ? "accept:observer" : "accept:rpc";
            MessageChannel requests = messaging.channel(requestChannel);
            requestSubscription = requests.onRequest(this::handleRequest);
            eventSubscription = messaging.channel("accept:events").subscribe(this::handleEvent);
            getLogger().info("ACCEPTANCE_PLUGIN_READY node=" + backendName + " role=" + role());
        } catch (RuntimeException failure) {
            fail("could not register acceptance channels: " + failure.getClass().getSimpleName());
        }
    }

    private CompletionStage<byte[]> handleRequest(Message request) {
        assertCallback("request", request);
        if (request.kind() != MessageKind.REQUEST) {
            fail("request handler received " + request.kind());
            throw new IllegalStateException("request handler received a non-request message");
        }
        requestCount.incrementAndGet();
        String command = new String(request.payload(), StandardCharsets.UTF_8);
        if (observer) {
            if (command.equals("stats") || command.equals("echo")) {
                return completed(summary(request));
            }
            return completed("unknown-observer-command=" + command);
        }
        if (command.equals("echo") || command.equals("stats")) {
            return completed(summary(request));
        }
        if (command.equals("cross")) {
            // Exercise backend-to-backend routing without blocking Bukkit's primary thread.
            return messaging.channel("accept:rpc").request(Endpoint.backend(otherBackend()),
                    bytes("echo")).thenApply(reply -> bytes("cross=" + new String(reply.payload(), StandardCharsets.UTF_8)
                    + ";correlation=" + (reply.kind() == MessageKind.REPLY && reply.replyTo() != null
                            && reply.replyTo().toString().equals(field(new String(reply.payload(), StandardCharsets.UTF_8), "id"))
                            && Endpoint.backend(otherBackend()).equals(reply.source())
                            && Endpoint.backend(backendName).equals(reply.target()) ? "valid" : "invalid")
                    + ";originRequestId=" + request.id()));
        }
        if (command.equals("proxy")) {
            return messaging.channel("accept:rpc").request(Endpoint.proxy(), bytes("proxy"))
                    .thenApply(reply -> bytes("proxy=" + new String(reply.payload(), StandardCharsets.UTF_8)
                            + ";correlation=" + (reply.kind() == MessageKind.REPLY && reply.replyTo() != null
                                    && reply.replyTo().toString().equals(field(new String(reply.payload(), StandardCharsets.UTF_8), "id"))
                                    && reply.source().isProxy() && Endpoint.backend(backendName).equals(reply.target())
                                    ? "valid" : "invalid") + ";originRequestId=" + request.id()));
        }
        if (command.equals("disable")) {
            // Reply first, then let the host observe normal Bukkit owner-disable cleanup.
            Bukkit.getScheduler().runTaskLater(this, () -> Bukkit.getPluginManager().disablePlugin(this), 20L);
            return completed("disable-scheduled;node=" + backendName + ";role=primary;id=" + request.id());
        }
        return completed("unknown-command=" + command);
    }

    private void handleEvent(Message message) {
        assertCallback("event", message);
        if (message.kind() != MessageKind.EVENT) {
            fail("event subscriber received " + message.kind());
            throw new IllegalStateException("event subscriber received a non-event message");
        }
        lastEventId = message.id().toString();
        eventCount.incrementAndGet();
    }

    private void assertCallback(String kind, Message message) {
        if (!Bukkit.isPrimaryThread()) {
            fail(kind + " callback did not run on Bukkit primary thread");
            throw new IllegalStateException("acceptance callback ran off the Bukkit primary thread");
        }
        boolean playersOnline = Bukkit.getWorlds().stream().anyMatch(world -> !world.getPlayers().isEmpty());
        if (playersOnline) {
            fail(kind + " callback ran while players were online");
            throw new IllegalStateException("acceptance smoke requires zero online players");
        }
        if (stopping) throw new IllegalStateException("plugin is stopping");
        if (message.id() == null || message.kind() == null || message.replyTo() != null) {
            fail(kind + " received an invalid request/event envelope");
            throw new IllegalStateException("unexpected request/event envelope");
        }
    }

    private String summary(Message request) {
        return "node=" + backendName + ";role=" + role()
                + ";main=" + Bukkit.isPrimaryThread()
                + ";events=" + eventCount.get()
                + ";lastEvent=" + lastEventId
                + ";requests=" + requestCount.get()
                + ";id=" + request.id()
                + ";replyTo=none"
                + ";source=" + request.source();
    }

    private static String field(String text, String name) {
        String marker = ";" + name + "=";
        int start = text.indexOf(marker);
        if (start < 0) return "";
        start += marker.length();
        int end = text.indexOf(';', start);
        return text.substring(start, end < 0 ? text.length() : end);
    }

    private String otherBackend() {
        return backendName.equals("a") ? "b" : "a";
    }

    private String role() { return observer ? "observer" : "primary"; }

    private static CompletionStage<byte[]> completed(String value) {
        return CompletableFuture.completedFuture(bytes(value));
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private void fail(String reason) {
        getLogger().severe("ACCEPTANCE_PLUGIN_FAIL node=" + backendName + " plugin=" + getName() + " reason=" + reason);
    }

    @Override public void onDisable() {
        stopping = true;
        // Leave the owner scope open here. The service's PluginDisableEvent listener must release it;
        // automatic owner cleanup is the lifecycle behavior under test.
        getLogger().info("ACCEPTANCE_PLUGIN_DISABLED node=" + backendName + " role=" + role());
    }
}

package dev.strataproxy.plugins.agent;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Runnable sample: args are endpoint, agent id, backend name, backend tcp URI, capacity. */
public final class AgentExampleMain {
    private AgentExampleMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException(
                "usage: AgentExampleMain <http-endpoint> <agent-id> <backend-name> <tcp-address> <capacity>");
        String configuredSecret = System.getenv("STRATAPROXY_AGENT_SECRET");
        if (configuredSecret == null || configuredSecret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalStateException("Set STRATAPROXY_AGENT_SECRET to the configured 32+ byte secret");
        var client = new AgentRegistrationClient(URI.create(args[0]), args[1], UUID.randomUUID(),
                configuredSecret.getBytes(StandardCharsets.UTF_8), args[2], URI.create(args[3]),
                Integer.parseInt(args[4]), 30);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { client.unregister(); } catch (Exception ignored) { /* The lease expires if shutdown is abrupt. */ }
        }, "strataproxy-agent-shutdown"));
        while (!Thread.currentThread().isInterrupted()) {
            client.registerOrHeartbeat();
            Thread.sleep(10_000);
        }
    }
}

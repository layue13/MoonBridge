package dev.strataproxy.smoke;

import dev.strataproxy.api.InitialPlacementHandler;
import dev.strataproxy.api.PlacementDecision;
import dev.strataproxy.api.PlayerIdentity;
import dev.strataproxy.api.PlayerView;
import dev.strataproxy.api.Plugin;
import dev.strataproxy.api.PluginContext;
import dev.strataproxy.api.ServerDefinition;
import dev.strataproxy.api.ServerRegistration;
import dev.strataproxy.api.TransferStatus;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Minimal external plugin used only by the installed Uranium transfer smoke. */
public final class UraniumTransferPlugin implements Plugin {
    private PluginContext context;
    private ServerRegistration targetRegistration;
    private volatile Thread worker;

    @Override public void onLoad(PluginContext loadedContext) {
        context = loadedContext;
        int port = Integer.parseInt(context.settings().get("newPort"));
        targetRegistration = context.servers().register(new ServerDefinition("new",
                URI.create("tcp://127.0.0.1:" + port), Map.of(), 10,
                Map.of("source", "uranium-smoke-plugin")));
        context.logger().info("SMOKE_PLUGIN_REGISTER_PASS name=new port={}", port);
    }

    @Override public Optional<InitialPlacementHandler> initialPlacementHandler() {
        return Optional.of((player, servers) -> {
            if (servers.stream().noneMatch(server -> server.name().equals("old"))
                    || servers.stream().noneMatch(server -> server.name().equals("new")
                            && "uranium-smoke-plugin".equals(server.metadata().get("source")))) {
                return CompletableFuture.completedFuture(PlacementDecision.reject("Missing Uranium smoke backend"));
            }
            worker = Thread.ofVirtual().name("uranium-transfer-smoke").start(() -> transferWhenConnected(player.identity()));
            return CompletableFuture.completedFuture(PlacementDecision.select("old"));
        });
    }

    private void transferWhenConnected(PlayerIdentity identity) {
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline) {
                PlayerView player = context.players().find(identity).orElse(null);
                if (player != null && player.currentServer().filter("old"::equals).isPresent()) {
                    context.players().transfer(identity, "new").whenComplete((result, failure) -> {
                        if (failure != null) {
                            context.logger().error("SMOKE_PLUGIN_TRANSFER_FAILED", failure);
                        } else if (result.status() == TransferStatus.NETWORK_READY) {
                            context.logger().info("SMOKE_PLUGIN_TRANSFER_PASS status=NETWORK_READY");
                        } else {
                            context.logger().error("SMOKE_PLUGIN_TRANSFER_FAILED status={} detail={}",
                                    result.status(), result.detail().orElse(""));
                        }
                    });
                    return;
                }
                Thread.sleep(20);
            }
            context.logger().error("SMOKE_PLUGIN_TRANSFER_FAILED player was not connected to old backend");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            context.logger().error("SMOKE_PLUGIN_TRANSFER_FAILED", failure);
        }
    }

    @Override public void onDisable() {
        Thread current = worker;
        if (current != null) current.interrupt();
        if (targetRegistration != null) targetRegistration.unregister();
    }
}

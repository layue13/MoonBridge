package dev.moonbridge.core.session;

import dev.moonbridge.api.PlacementDecision;
import dev.moonbridge.core.backend.BackendId;
import dev.moonbridge.core.backend.BackendView;
import dev.moonbridge.core.session.channel.SessionChannels;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Picks the player's first backend: turns a placement decision into an ordered candidate list and dials
 * it until one connects. Retries only before a backend has received any Minecraft handshake/login bytes.
 * Confined to the frontend event loop.
 */
final class InitialRouter {
    private static final Logger LOGGER = LoggerFactory.getLogger(InitialRouter.class);

    /** Outcome callbacks; {@code connected} gets a live channel that nothing has been written to yet. */
    interface Listener {
        void connected(Channel backend, BackendView target);
        void reject(Component reason);
        void close();
    }

    private final ProxySessionListener owner;
    private final Channel frontend;
    private final BooleanSupplier shuttingDown;
    private final Listener listener;
    private List<String> candidates = List.of();
    private int nextCandidate;
    private long deadlineNanos;
    private String username;
    private Channel dialing;

    InitialRouter(ProxySessionListener owner, Channel frontend, BooleanSupplier shuttingDown, Listener listener) {
        this.owner = owner;
        this.frontend = frontend;
        this.shuttingDown = shuttingDown;
        this.listener = listener;
    }

    /** Call when placement starts; the whole routing budget runs from here. */
    void startClock() { deadlineNanos = System.nanoTime() + owner.placementTimeout().toNanos(); }

    void route(String username, Optional<PlacementDecision> decision) {
        this.username = username;
        if (decision.isPresent()) {
            if (decision.get() instanceof PlacementDecision.Reject rejected) {
                listener.reject(rejected.reason());
                return;
            }
            if (!(decision.get() instanceof PlacementDecision.Select selected)) {
                listener.close();
                return;
            }
            candidates = selected.backendNames();
        } else {
            candidates = owner.initialServers();
        }
        nextCandidate = 0;
        if (candidates.isEmpty()) {
            reject("No entry servers are configured.");
            return;
        }
        dialNext();
    }

    /** Closes an in-flight dial that never became the session's backend. */
    void cancel() {
        Channel channel = dialing;
        if (channel != null) channel.close();
    }

    private void reject(String reason) { listener.reject(Component.text(reason)); }

    private void dialNext() {
        if (shuttingDown.getAsBoolean()) return;
        while (nextCandidate < candidates.size()) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                reject("Initial server routing timed out.");
                return;
            }
            BackendView target = owner.catalog().find(new BackendId(candidates.get(nextCandidate++))).orElse(null);
            if (target == null || !SessionChannels.isTcpAddress(target.address())) continue;
            dial(target, remaining);
            return;
        }
        reject("No entry server could be reached.");
    }

    private void dial(BackendView target, long remainingNanos) {
        // The session is not attached to this channel until the attempt has won. A failed
        // dial's inactive/exception callbacks must not close a later connection.
        Bootstrap bootstrap = SessionChannels.backendBootstrap(frontend, owner.transport(), owner.backendResolver(),
                (int) Math.max(1, Math.min(5000, TimeUnit.NANOSECONDS.toMillis(remainingNanos))), false,
                pipeline -> { });
        ChannelFuture connect;
        try {
            connect = bootstrap.connect(SessionChannels.socketAddress(target.address()));
        } catch (RuntimeException failure) {
            LOGGER.debug("Initial backend dial could not start for {} to {}", username, target.address(), failure);
            dialNext();
            return;
        }
        dialing = connect.channel();
        connect.addListener(ignored -> finish(connect, target));
    }

    private void finish(ChannelFuture connect, BackendView target) {
        if (!frontend.eventLoop().inEventLoop()) {
            try {
                frontend.eventLoop().execute(() -> finish(connect, target));
            } catch (RejectedExecutionException shutdown) {
                connect.channel().close();
            }
            return;
        }
        if (shuttingDown.getAsBoolean() || dialing != connect.channel()) {
            connect.channel().close();
            return;
        }
        if (!connect.isSuccess()) {
            LOGGER.debug("Initial backend connection failed for player {} to {}", username, target.address(),
                    connect.cause());
            retry(connect);
            return;
        }
        BackendView current = owner.catalog().find(target.handle().id()).orElse(null);
        if (current == null || !current.handle().equals(target.handle())
                || !current.address().equals(target.address())) {
            retry(connect);
            return;
        }
        if (deadlineNanos - System.nanoTime() <= 0) {
            reject("Initial server routing timed out.");
            return;
        }
        dialing = null;
        candidates = List.of();
        listener.connected(connect.channel(), current);
    }

    private void retry(ChannelFuture connect) {
        dialing = null;
        connect.channel().close();
        dialNext();
    }
}

package dev.moonbridge.core.session;

import dev.moonbridge.api.DisconnectResult;
import dev.moonbridge.api.MessageResult;
import dev.moonbridge.core.protocol.Minecraft1710PlayPackets;
import dev.moonbridge.core.protocol.MinecraftLoginDisconnect;
import dev.moonbridge.core.protocol.MinecraftText;
import dev.moonbridge.core.relay.RawRelay;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import net.kyori.adventure.text.Component;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What the proxy says to a connected player on its own behalf: chat messages and the final disconnect.
 * A disconnect waits for the relay to drain and for in-flight messages to be written, so the reason is
 * the last thing the client sees. Everything but the message counter runs on the frontend event loop.
 */
final class ClientControl {
    private static final int MAX_PENDING_MESSAGES = 64;
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(5);

    /** The session state this class reads, and the transitions it asks the session to make. */
    interface Host {
        boolean closed();
        boolean disconnecting();
        /** The client is in PLAY with a live relay and no transfer or Forge negotiation in progress. */
        boolean chatReady();
        boolean playPhase();
        RawRelay.Link relay();
        /** Marks the session as disconnecting: stops reads, closes tab completion, cancels login/play deadlines. */
        void enterDisconnecting();
        void closeSession();
    }

    private final Channel frontend;
    private final Host host;
    private final AtomicInteger pendingMessages = new AtomicInteger();
    private boolean packetStarted;
    private boolean relayDrained;
    private String reason;
    private CompletableFuture<DisconnectResult> result;
    private ScheduledFuture<?> deadline;

    ClientControl(Channel frontend, Host host) {
        this.frontend = frontend;
        this.host = host;
    }

    // ---- chat messages

    CompletionStage<MessageResult> sendMessage(String message) {
        validateMessage(message);
        return sendMessage(Component.text(message));
    }

    CompletionStage<MessageResult> sendMessage(Component message) {
        return sendEncodedMessage(MinecraftText.encode(message));
    }

    CompletionStage<MessageResult> sendEncodedMessage(String message) {
        CompletableFuture<MessageResult> outcome = new CompletableFuture<>();
        if (host.closed() || host.disconnecting()) {
            outcome.complete(MessageResult.NOT_CONNECTED);
            return outcome;
        }
        while (true) {
            int pending = pendingMessages.get();
            if (pending >= MAX_PENDING_MESSAGES) {
                outcome.complete(MessageResult.BACKPRESSURED);
                return outcome;
            }
            if (pendingMessages.compareAndSet(pending, pending + 1)) break;
        }
        Runnable send = () -> write(message, outcome);
        if (frontend.eventLoop().inEventLoop()) send.run();
        else {
            try { frontend.eventLoop().execute(send); }
            catch (RejectedExecutionException shutdown) {
                pendingMessages.decrementAndGet();
                outcome.complete(MessageResult.NOT_CONNECTED);
            }
        }
        return outcome;
    }

    private void write(String message, CompletableFuture<MessageResult> outcome) {
        if (host.closed() || !frontend.isActive() || host.disconnecting()) {
            reject(outcome, MessageResult.NOT_CONNECTED);
        } else if (!host.chatReady()) {
            reject(outcome, MessageResult.NOT_READY);
        } else if (!frontend.isWritable()) {
            reject(outcome, MessageResult.BACKPRESSURED);
        } else {
            try {
                ByteBuf frame = Minecraft1710PlayPackets.chatReplyEncoded(frontend.alloc(), message);
                frontend.writeAndFlush(frame).addListener(write -> {
                    pendingMessages.decrementAndGet();
                    if (write.isSuccess()) outcome.complete(MessageResult.SENT);
                    else {
                        outcome.completeExceptionally(write.cause());
                        host.closeSession();
                    }
                    if (host.disconnecting()) writeReasonIfDrained();
                });
            } catch (RuntimeException failure) {
                pendingMessages.decrementAndGet();
                outcome.completeExceptionally(failure);
                if (host.disconnecting()) writeReasonIfDrained();
            }
        }
    }

    private void reject(CompletableFuture<MessageResult> outcome, MessageResult status) {
        pendingMessages.decrementAndGet();
        if (host.disconnecting()) writeReasonIfDrained();
        outcome.complete(status);
    }

    static void validateMessage(String message) {
        if (message == null || message.codePointCount(0, message.length()) > 1024) {
            throw new IllegalArgumentException("message must contain at most 1024 Unicode code points");
        }
    }

    // ---- disconnect

    CompletionStage<DisconnectResult> disconnect(String text) {
        validateDisconnectReason(text);
        return disconnectEncoded(MinecraftText.encodeReason(Component.text(text)));
    }

    CompletionStage<DisconnectResult> disconnectEncoded(String encodedReason) {
        CompletableFuture<DisconnectResult> requested = new CompletableFuture<>();
        Runnable command = () -> {
            if (host.closed()) {
                requested.complete(DisconnectResult.NOT_CONNECTED);
                return;
            }
            begin(encodedReason);
            // Listener shutdown can set closed from another thread between the check above
            // and begin. That path never creates a draining-disconnect future.
            if (result == null) requested.complete(DisconnectResult.NOT_CONNECTED);
            else result.whenComplete((value, failure) -> {
                if (failure == null) requested.complete(value);
                else requested.completeExceptionally(failure);
            });
        };
        if (frontend.eventLoop().inEventLoop()) command.run();
        else {
            try { frontend.eventLoop().execute(command); }
            catch (RejectedExecutionException shutdown) { requested.complete(DisconnectResult.NOT_CONNECTED); }
        }
        return requested;
    }

    static void validateDisconnectReason(String text) {
        if (text == null || text.isBlank() || text.codePointCount(0, text.length()) > 1024) {
            throw new IllegalArgumentException("disconnect reason must contain 1 to 1024 Unicode code points");
        }
    }

    /** Starts a drained disconnect; a no-op once the session is closed or already disconnecting. */
    void begin(String encodedReason) {
        if (host.closed() || host.disconnecting()) return;
        host.enterDisconnecting();
        reason = encodedReason;
        result = new CompletableFuture<>();
        try {
            deadline = frontend.eventLoop().schedule(host::closeSession, DRAIN_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
            RawRelay.Link relay = host.relay();
            if (relay == null) {
                relayDrained = true;
                writeReasonIfDrained();
            } else {
                relay.pause().whenComplete((ignored, failure) -> {
                    try {
                        frontend.eventLoop().execute(() -> {
                            if (!host.disconnecting() || host.closed()) return;
                            if (failure != null) host.closeSession();
                            else {
                                relayDrained = true;
                                writeReasonIfDrained();
                            }
                        });
                    } catch (RejectedExecutionException shutdown) {
                        host.closeSession();
                    }
                });
            }
        } catch (RuntimeException shutdown) {
            host.closeSession();
        }
    }

    /** The backend already sent its own login disconnect; only the close notification is still owed. */
    void expectClose() { result = new CompletableFuture<>(); }

    /** Session teardown: stops the drain timer and reports the player as disconnected. */
    void closed() {
        if (deadline != null) deadline.cancel(false);
        if (result != null) result.complete(DisconnectResult.DISCONNECTED);
    }

    private void writeReasonIfDrained() {
        if (!host.disconnecting() || !relayDrained || packetStarted || host.closed()
                || pendingMessages.get() != 0) return;
        packetStarted = true;
        if (!frontend.isActive()) {
            host.closeSession();
            return;
        }
        try {
            ByteBuf packet;
            if (host.playPhase()) {
                ByteBuf payload = Minecraft1710PlayPackets.disconnectEncoded(frontend.alloc(), reason);
                try {
                    packet = frontend.pipeline().get("minecraft-frame-encoder") == null
                            ? Minecraft1710PlayPackets.frame(frontend.alloc(), payload) : payload.copy();
                } finally {
                    payload.release();
                }
            } else {
                packet = MinecraftLoginDisconnect.encodeJson(frontend.alloc(), reason);
            }
            frontend.writeAndFlush(packet).addListener(ignored -> host.closeSession());
        } catch (RuntimeException failure) {
            host.closeSession();
        }
    }
}

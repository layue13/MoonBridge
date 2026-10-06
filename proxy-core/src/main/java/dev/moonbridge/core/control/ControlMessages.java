package dev.moonbridge.core.control;

import dev.moonbridge.messaging.Endpoint;
import dev.moonbridge.messaging.Message;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static dev.moonbridge.core.control.ControlFailures.*;
import static dev.moonbridge.core.control.ControlMessages.*;
import static dev.moonbridge.core.control.ControlProtocol.*;

/** Message and deadline helpers shared by the control routing code. */
final class ControlMessages {
    private ControlMessages() { }

    static Message retarget(Message message, Endpoint target) {
        return new Message(message.id(), message.kind(), message.channel(), message.source(), target,
                message.replyTo(), message.payload());
    }

    static String namespace(String channel) { return channel.substring(0, channel.indexOf(':')); }

    static long deadlineAfterMillis(long timeoutMillis) {
        long now = System.nanoTime();
        long nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        return now > Long.MAX_VALUE - nanos ? Long.MAX_VALUE : now + nanos;
    }

    static Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        return nanos <= 0 ? Duration.ZERO : Duration.ofNanos(nanos);
    }
}

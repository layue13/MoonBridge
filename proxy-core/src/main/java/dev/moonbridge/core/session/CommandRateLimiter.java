package dev.moonbridge.core.session;

import java.util.concurrent.TimeUnit;

/** Token bucket for proxy-owned player commands; confined to the session event loop. */
final class CommandRateLimiter {
    enum Decision { ADMIT, DENY, DENY_WITH_NOTICE }

    static final int BURST = 10;
    static final long TOKEN_NANOS = TimeUnit.MILLISECONDS.toNanos(200);
    static final long NOTICE_NANOS = TimeUnit.SECONDS.toNanos(2);

    private int tokens = BURST;
    private long refillNanos;
    private long lastNoticeNanos;
    private boolean noticeSent;

    CommandRateLimiter(long nowNanos) {
        refillNanos = nowNanos;
    }

    Decision admit(long nowNanos) {
        long elapsed = nowNanos - refillNanos;
        if (elapsed >= TOKEN_NANOS) {
            long replenished = Math.min(BURST, elapsed / TOKEN_NANOS);
            tokens = (int) Math.min(BURST, tokens + replenished);
            refillNanos = nowNanos;
        }
        if (tokens > 0) {
            tokens--;
            return Decision.ADMIT;
        }
        if (!noticeSent || nowNanos - lastNoticeNanos >= NOTICE_NANOS) {
            noticeSent = true;
            lastNoticeNanos = nowNanos;
            return Decision.DENY_WITH_NOTICE;
        }
        return Decision.DENY;
    }
}

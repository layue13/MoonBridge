package dev.moonbridge.core.session.play;

import org.junit.jupiter.api.Test;

import static dev.moonbridge.core.session.play.CommandRateLimiter.Decision.ADMIT;
import static dev.moonbridge.core.session.play.CommandRateLimiter.Decision.DENY;
import static dev.moonbridge.core.session.play.CommandRateLimiter.Decision.DENY_WITH_NOTICE;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class CommandRateLimiterTest {
    @Test
    void admitsABurstThenRefillsOneTokenPerInterval() {
        long now = 1_000L;
        var limiter = new CommandRateLimiter(now);
        for (int i = 0; i < CommandRateLimiter.BURST; i++) assertEquals(ADMIT, limiter.admit(now));

        assertEquals(DENY_WITH_NOTICE, limiter.admit(now));
        assertEquals(DENY, limiter.admit(now + CommandRateLimiter.TOKEN_NANOS - 1));

        now += CommandRateLimiter.TOKEN_NANOS;
        assertEquals(ADMIT, limiter.admit(now));
        assertEquals(DENY, limiter.admit(now));
    }

    @Test
    void repeatsTheNoticeOnlyAfterTheNoticeInterval() {
        var limiter = new CommandRateLimiter(0);
        for (int i = 0; i < CommandRateLimiter.BURST; i++) limiter.admit(0);
        assertEquals(DENY_WITH_NOTICE, limiter.admit(0));

        long later = CommandRateLimiter.NOTICE_NANOS;
        int refilled = 0;
        while (limiter.admit(later) == ADMIT) refilled++;
        assertEquals(CommandRateLimiter.BURST, refilled, "two seconds refill a full burst");
        assertEquals(DENY, limiter.admit(later), "the notice was just repeated by the draining call");
    }

    @Test
    void refillsAtMostOneBurst() {
        var limiter = new CommandRateLimiter(0);
        for (int i = 0; i < CommandRateLimiter.BURST; i++) limiter.admit(0);

        long later = CommandRateLimiter.TOKEN_NANOS * 100;
        for (int i = 0; i < CommandRateLimiter.BURST; i++) assertEquals(ADMIT, limiter.admit(later));
        assertEquals(DENY_WITH_NOTICE, limiter.admit(later));
    }
}

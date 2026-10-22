package dev.moonbridge.core.log;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogTextTest {
    @Test
    void controlAndBidirectionalCharactersCannotForgeLogLines() {
        assertEquals("Steve?2026-10-22 INFO fake", LogText.clean("Steve\n2026-10-22 INFO fake"));
        assertEquals("a?b?c?d", LogText.clean("a\rb c‮d"));
        assertEquals("plain_Name", LogText.clean("plain_Name"));
        assertEquals("<none>", LogText.clean(null));
    }

    @Test
    void longTextIsCutWithoutSplittingASurrogatePair() {
        String cut = LogText.clean("x".repeat(LogText.MAX_LENGTH + 20));
        assertEquals(LogText.MAX_LENGTH + 1, cut.length());
        assertEquals('…', cut.charAt(cut.length() - 1));
        String emoji = "x".repeat(LogText.MAX_LENGTH - 1) + "🙂🙂";
        assertEquals("x".repeat(LogText.MAX_LENGTH - 1) + "…", LogText.clean(emoji));
    }
}

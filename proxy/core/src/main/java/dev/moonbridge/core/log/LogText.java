package dev.moonbridge.core.log;

/**
 * Makes peer- and plugin-supplied text safe to put in a log line. OFFLINE mode does not restrict the characters of a
 * username, so without this a name containing a line break could forge log lines, and a long kick reason could flood
 * them.
 */
public final class LogText {
    /** Longest name or reason kept in a log line; longer text is cut and marked with an ellipsis. */
    public static final int MAX_LENGTH = 128;

    private LogText() { }

    /** Replaces control and bidirectional-override characters with '?' and caps the length. */
    public static String clean(String text) {
        if (text == null) return "<none>";
        int limit = Math.min(text.length(), MAX_LENGTH);
        if (limit < text.length() && Character.isHighSurrogate(text.charAt(limit - 1))) limit--;
        StringBuilder cleaned = new StringBuilder(limit + 1);
        for (int i = 0; i < limit; i++) {
            char character = text.charAt(i);
            cleaned.append(unsafe(character) ? '?' : character);
        }
        if (text.length() > MAX_LENGTH) cleaned.append('…');
        return cleaned.toString();
    }

    private static boolean unsafe(char character) {
        return Character.isISOControl(character)
                || character == ' ' || character == ' ' // line and paragraph separators
                || (character >= '‪' && character <= '‮') // bidirectional embeddings and overrides
                || (character >= '⁦' && character <= '⁩'); // bidirectional isolates
    }
}

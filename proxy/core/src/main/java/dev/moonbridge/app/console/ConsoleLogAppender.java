package dev.moonbridge.app.console;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.encoder.Encoder;
import org.jline.reader.LineReader;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

import java.nio.charset.StandardCharsets;

/**
 * Console log output that keeps the interactive prompt at the bottom: while a JLine reader is attached, each line is
 * printed above the prompt, with warnings and errors coloured; otherwise lines go to standard output unchanged, so
 * Docker, systemd and panel consoles see plain text.
 */
public final class ConsoleLogAppender extends AppenderBase<ILoggingEvent> {
    private static volatile LineReader reader;

    private Encoder<ILoggingEvent> encoder;

    public void setEncoder(Encoder<ILoggingEvent> encoder) { this.encoder = encoder; }

    @Override public void start() {
        if (encoder == null) {
            addError("No encoder set for the console appender " + name);
            return;
        }
        super.start();
    }

    @Override protected void append(ILoggingEvent event) {
        String text = new String(encoder.encode(event), StandardCharsets.UTF_8);
        if (text.endsWith("\n")) text = text.substring(0, text.length() - (text.endsWith("\r\n") ? 2 : 1));
        print(text, event.getLevel());
    }

    /** Prints command output the same way as log lines, without a level. */
    static void printLine(String line) { print(line, null); }

    static void attach(LineReader lineReader) { reader = lineReader; }

    static void detach() { reader = null; }

    private static void print(String text, Level level) {
        LineReader current = reader;
        if (current == null) {
            System.out.println(text);
            return;
        }
        AttributedStyle style = level == Level.ERROR ? AttributedStyle.DEFAULT.foreground(AttributedStyle.RED)
                : level == Level.WARN ? AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW)
                : AttributedStyle.DEFAULT;
        try {
            current.printAbove(new AttributedString(text, style));
        } catch (RuntimeException terminalClosed) {
            // JLine closes the terminal from its own shutdown hook while the proxy is still logging its shutdown.
            reader = null;
            System.out.println(text);
        }
    }
}

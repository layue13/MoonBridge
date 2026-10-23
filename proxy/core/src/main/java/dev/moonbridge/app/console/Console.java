package dev.moonbridge.app.console;

import dev.moonbridge.core.plugin.PluginHost;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jline.reader.Candidate;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The proxy's console. In an interactive terminal it uses JLine: the prompt stays at the bottom while logs scroll above
 * it, with history and Tab completion of commands, players, servers and log areas. Without a terminal (Docker logs,
 * systemd, hosting panels) it reads plain lines from standard input. Either way a line goes to the built-in commands
 * first and then to plugin console commands. The reader thread is a daemon and never holds up shutdown.
 */
public final class Console implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(Console.class);
    private static final int MAX_LINE = 8192;

    private final BuiltinCommands builtins;
    private final PluginHost plugins;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread reader;
    private volatile Terminal terminal;

    private Console(BuiltinCommands builtins, PluginHost plugins) {
        this.builtins = builtins;
        this.plugins = plugins;
        boolean interactive = System.console() != null && System.console().isTerminal();
        reader = Thread.ofPlatform().daemon().name("moonbridge-console")
                .unstarted(interactive ? this::readInteractive : this::readPlain);
    }

    public static Console start(BuiltinCommands builtins, PluginHost plugins) {
        Console console = new Console(builtins, plugins);
        console.reader.start();
        return console;
    }

    /** Output for command results, printed above the prompt when there is one. */
    public static void print(String line) { ConsoleLogAppender.printLine(line); }

    void dispatch(String line) {
        String command = line.strip();
        if (command.isEmpty() || builtins.run(command)) return;
        if (!plugins.dispatchConsoleCommand(command,
                component -> print(PlainTextComponentSerializer.plainText().serialize(component)))) {
            print("Unknown command. Type help for the built-in commands.");
        }
    }

    private void readInteractive() {
        Terminal opened;
        LineReader lineReader;
        try {
            opened = TerminalBuilder.builder().system(true).name("MoonBridge").build();
            lineReader = LineReaderBuilder.builder()
                    .terminal(opened)
                    .appName("MoonBridge")
                    .completer((ignored, parsed, candidates) -> complete(parsed.words(), parsed.wordIndex(), candidates))
                    .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
                    .build();
        } catch (IOException | RuntimeException unavailable) {
            LOGGER.warn("Interactive console unavailable ({}); reading plain lines instead", unavailable.toString());
            readPlain();
            return;
        }
        terminal = opened;
        ConsoleLogAppender.attach(lineReader);
        try (opened) {
            while (!closed.get()) {
                String line;
                try {
                    line = lineReader.readLine("> ");
                } catch (UserInterruptException interrupt) {
                    // The terminal is in raw mode, so Ctrl+C arrives here instead of as a signal.
                    dispatch("stop");
                    return;
                } catch (EndOfFileException endOfInput) {
                    if (!closed.get()) LOGGER.info("Console input closed; the proxy keeps running");
                    return;
                }
                dispatch(line);
            }
        } catch (IOException | RuntimeException closedUnderneath) {
            // During shutdown JLine closes the terminal while a read is waiting; that only ends the console.
            LOGGER.debug("Console reader stopped", closedUnderneath);
        } finally {
            ConsoleLogAppender.detach();
        }
    }

    private void complete(List<String> words, int index, List<Candidate> candidates) {
        List<String> options = new ArrayList<>(builtins.complete(words, index));
        if (index == 0) options.addAll(plugins.consoleCommandNames());
        for (String option : options) candidates.add(new Candidate(option));
    }

    private void readPlain() {
        var source = new InputStreamReader(System.in, StandardCharsets.UTF_8);
        var line = new StringBuilder();
        boolean tooLong = false;
        try {
            int character;
            while (!closed.get() && (character = source.read()) != -1) {
                if (character == '\n') {
                    if (tooLong) print("Console command exceeds " + MAX_LINE + " characters.");
                    else dispatch(line.toString());
                    line.setLength(0);
                    tooLong = false;
                } else if (character != '\r') {
                    if (line.length() < MAX_LINE) line.append((char) character);
                    else tooLong = true;
                }
            }
            if (!closed.get() && !tooLong && !line.isEmpty()) dispatch(line.toString());
        } catch (IOException failure) {
            if (!closed.get()) print("Console input is unavailable: " + failure.getMessage());
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        ConsoleLogAppender.detach();
        reader.interrupt();
        Terminal current = terminal;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
                // The JVM is stopping; the terminal is restored by JLine's own shutdown handling.
            }
        }
    }
}

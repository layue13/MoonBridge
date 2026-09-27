package dev.moonbridge.app;

import dev.moonbridge.core.plugin.PluginHost;
import net.kyori.adventure.text.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Reads trusted local stdin without closing it or holding up proxy shutdown. */
final class ConsoleCommands implements AutoCloseable {
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread reader;

    ConsoleCommands(PluginHost plugins, InputStream input, Consumer<Component> reply) {
        reader = Thread.ofPlatform().daemon().name("moonbridge-console").start(() -> {
            var source = new InputStreamReader(input, StandardCharsets.UTF_8);
            var line = new StringBuilder();
            boolean tooLong = false;
            try {
                int character;
                while (!closed.get() && (character = source.read()) != -1) {
                    if (closed.get()) break;
                    if (character == '\n') {
                        if (tooLong) reply.accept(Component.text("Console command exceeds 8192 characters."));
                        else dispatch(plugins, line.toString(), reply);
                        line.setLength(0);
                        tooLong = false;
                    } else if (character != '\r') {
                        if (line.length() < 8192) line.append((char) character);
                        else tooLong = true;
                    }
                }
                if (!closed.get() && !tooLong && !line.isEmpty()) dispatch(plugins, line.toString(), reply);
            } catch (IOException failure) {
                if (!closed.get()) reply.accept(Component.text("Console input is unavailable: " + failure.getMessage()));
            }
        });
    }

    private static void dispatch(PluginHost plugins, String line, Consumer<Component> reply) {
        String command = line.trim();
        if (!command.isEmpty() && !plugins.dispatchConsoleCommand(command, reply)) {
            reply.accept(Component.text("Unknown console command."));
        }
    }

    @Override public void close() {
        closed.set(true);
        reader.interrupt();
    }
}

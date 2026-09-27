package dev.moonbridge.api;

import java.util.Objects;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;

/** One proxy command. The raw arguments exclude the root name and its separator. */
public final class CommandInvocation {
    private final CommandSource source;
    private final String name;
    private final String arguments;
    private final Consumer<Component> reply;

    public CommandInvocation(PlayerView player, String name, String arguments, Consumer<Component> reply) {
        this(CommandSource.player(player, reply), name, arguments);
    }

    public CommandInvocation(CommandSource source, String name, String arguments) {
        this.source = Objects.requireNonNull(source, "source");
        this.name = Objects.requireNonNull(name, "name");
        this.arguments = Objects.requireNonNull(arguments, "arguments");
        this.reply = source::reply;
    }

    /** Returns the player for player-issued commands; use {@link #source()} for console-capable commands. */
    public PlayerView player() {
        return source.player().orElseThrow(() -> new IllegalStateException("Command was issued by console"));
    }
    public CommandSource source() { return source; }
    public String name() { return name; }
    public String arguments() { return arguments; }

    /** Queues rich text if the session is connected and writable; excess replies may be dropped. */
    public void reply(Component message) {
        reply.accept(Objects.requireNonNull(message, "message"));
    }

    /** Queues plain text, retaining the 1024 Unicode code point convenience limit. */
    public void reply(String message) {
        PlainTextValidation.validateString(message, true, "message");
        reply.accept(Component.text(message));
    }
}

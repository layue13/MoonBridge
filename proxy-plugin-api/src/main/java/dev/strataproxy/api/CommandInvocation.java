package dev.strataproxy.api;

import java.util.Objects;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;

/** One player command. The raw arguments exclude the leading command name and separator. */
public final class CommandInvocation {
    private final PlayerView player;
    private final String name;
    private final String arguments;
    private final Consumer<Component> reply;

    public CommandInvocation(PlayerView player, String name, String arguments, Consumer<Component> reply) {
        this.player = Objects.requireNonNull(player, "player");
        this.name = Objects.requireNonNull(name, "name");
        this.arguments = Objects.requireNonNull(arguments, "arguments");
        this.reply = Objects.requireNonNull(reply, "reply");
    }

    public PlayerView player() { return player; }
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

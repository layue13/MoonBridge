package dev.moonbridge.api;

import java.util.Optional;
import java.util.Objects;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;

/** Player or trusted local console issuing a proxy command. */
public interface CommandSource {
    /** Empty for console commands. */
    Optional<PlayerView> player();

    default boolean isConsole() {
        return player().isEmpty();
    }

    void reply(Component message);

    static CommandSource player(PlayerView player, Consumer<Component> reply) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(reply, "reply");
        return new CommandSource() {
            @Override public Optional<PlayerView> player() { return Optional.of(player); }
            @Override public void reply(Component message) { reply.accept(Objects.requireNonNull(message, "message")); }
        };
    }

    static CommandSource console(Consumer<Component> reply) {
        Objects.requireNonNull(reply, "reply");
        return new CommandSource() {
            @Override public Optional<PlayerView> player() { return Optional.empty(); }
            @Override public void reply(Component message) { reply.accept(Objects.requireNonNull(message, "message")); }
        };
    }
}

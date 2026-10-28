package dev.moonbridge.api.event;

import dev.moonbridge.api.AccessDecision;
import dev.moonbridge.api.PlayerView;
import java.util.Objects;

/**
 * A check before a player-typed proxy command runs, after the root command is known to be registered
 * and before its permission check and handler. {@code command} is the lower-case root name and
 * {@code arguments} the unsplit rest. Denying consumes the command and sends the reason to the player.
 * Listener failure or timeout also denies. Unknown commands, console commands and commands a plugin
 * runs through {@code Commands.execute} do not raise this event. Typical use is holding back every
 * proxy command until a login plugin has authenticated an offline-mode player.
 */
public record PlayerCommandEvent(PlayerView player, String command, String arguments)
        implements Event<AccessDecision> {
    public PlayerCommandEvent {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(arguments, "arguments");
    }
}

package dev.strataproxy.plugin.command;

/**
 * Sender capable of invoking commands and receiving command feedback.
 */
public interface CommandSource {
    /**
 * Provides name.
 *
     * @return display name for diagnostics and feedback
     */
    String name();

    /**
 * Provides player.
 *
     * @return {@code true} when the source represents an in-game player
     */
    default boolean player() {
        return true;
    }

    /**
     * Tests whether the source is allowed to run a command.
     *
     * @param permission permission string from the command definition
     * @return {@code true} when the permission is blank or the source has it
     */
    default boolean hasPermission(String permission) {
        return permission == null || permission.isBlank();
    }

    /**
     * Sends user-visible feedback to the source.
     *
     * @param message feedback text
     */
    default void sendMessage(String message) {
    }
}

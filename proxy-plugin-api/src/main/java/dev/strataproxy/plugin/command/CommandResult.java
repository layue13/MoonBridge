package dev.strataproxy.plugin.command;

/**
 * Result returned by command execution.
 *
 * @param handled whether a command matched and processed the input
 * @param success whether the handled command completed successfully
 * @param message optional feedback for the command source
 */
public record CommandResult(boolean handled, boolean success, String message) {
    /**
     * Validates and normalizes record components.
     */
    public CommandResult {
        message = message == null ? "" : message;
    }

    /**
 * Documents this public API element.
 *
     * @return result for input that did not match a command
     */
    public static CommandResult ignored() {
        return new CommandResult(false, true, "");
    }

    /**
 * Documents this public API element.
 *
     * @return successful handled result with no message
     */
    public static CommandResult ok() {
        return new CommandResult(true, true, "");
    }

    /**
 * Documents this public API element.
 *
     * @param message feedback to send to the command source
     * @return successful handled result
     */
    public static CommandResult ok(String message) {
        return new CommandResult(true, true, message);
    }

    /**
 * Documents this public API element.
 *
     * @param message error feedback to send to the command source
     * @return failed handled result
     */
    public static CommandResult failure(String message) {
        return new CommandResult(true, false, message);
    }
}

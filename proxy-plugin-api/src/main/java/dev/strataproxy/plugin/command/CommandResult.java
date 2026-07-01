package dev.strataproxy.plugin.command;

public record CommandResult(boolean handled, boolean success, String message) {
    public CommandResult {
        message = message == null ? "" : message;
    }

    public static CommandResult ignored() {
        return new CommandResult(false, true, "");
    }

    public static CommandResult ok() {
        return new CommandResult(true, true, "");
    }

    public static CommandResult ok(String message) {
        return new CommandResult(true, true, message);
    }

    public static CommandResult failure(String message) {
        return new CommandResult(true, false, message);
    }
}

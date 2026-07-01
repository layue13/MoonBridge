package dev.strataproxy.plugin.command;

public interface CommandSource {
    String name();

    default boolean player() {
        return true;
    }

    default boolean hasPermission(String permission) {
        return permission == null || permission.isBlank();
    }

    default void sendMessage(String message) {
    }
}

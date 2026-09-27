package dev.strataproxy.core.control;

/** No proxy plugin currently owns the requested backend message channel. */
public final class BackendNoHandlerException extends IllegalStateException {
    public BackendNoHandlerException(String channel) {
        super("No active plugin handler for backend channel " + channel);
    }
}

package dev.strataproxy.api.event;

/**
 * A handle for a registered event listener. Closing it more than once has no additional effect.
 * Closing prevents subsequent selection by the dispatcher; it does not wait for or retract a
 * callback already selected for execution, including its asynchronous stage.
 */
public interface EventSubscription extends AutoCloseable {
    @Override
    void close();
}

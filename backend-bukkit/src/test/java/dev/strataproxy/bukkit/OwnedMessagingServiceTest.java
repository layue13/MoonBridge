package dev.strataproxy.bukkit;

import dev.strataproxy.messaging.MessageChannel;
import dev.strataproxy.messaging.Messaging;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class OwnedMessagingServiceTest {
    @Test void consumersShareProviderButOwnIndependentScopesAndExecutors() {
        List<Scope> opened = new ArrayList<Scope>();
        List<Plugin> scheduledOwners = new ArrayList<Plugin>();
        OwnedMessagingService service = new OwnedMessagingService((owner, executor) -> {
            Scope scope = new Scope(executor);
            opened.add(scope);
            return scope;
        }, owner -> task -> { scheduledOwners.add(owner); task.run(); });
        Plugin a = plugin("A", new AtomicBoolean(true));
        Plugin b = plugin("B", new AtomicBoolean(true));
        Messaging first = service.forPlugin(a);
        assertSame(first, service.forPlugin(a));
        Messaging second = service.forPlugin(b);
        assertNotSame(first, second);
        assertEquals(2, opened.size());
        opened.get(0).executor.execute(() -> { });
        opened.get(1).executor.execute(() -> { });
        assertSame(a, scheduledOwners.get(0));
        assertSame(b, scheduledOwners.get(1));
        service.release(a);
        assertTrue(opened.get(0).closed);
        assertFalse(opened.get(1).closed);
        assertSame(second, service.forPlugin(b));
        service.close();
        assertTrue(opened.get(1).closed);
        assertThrows(IllegalStateException.class, () -> service.forPlugin(b));
    }

    @Test void disabledOwnerCannotReopenScopeAndPluginReloadGetsFreshScope() {
        List<Scope> opened = new ArrayList<Scope>();
        OwnedMessagingService service = new OwnedMessagingService((owner, executor) -> {
            Scope scope = new Scope(executor);
            opened.add(scope);
            return scope;
        }, owner -> Runnable::run);
        AtomicBoolean enabled = new AtomicBoolean(true);
        Plugin beforeReload = plugin("A", enabled);
        Messaging old = service.forPlugin(beforeReload);
        enabled.set(false);
        service.release(beforeReload);
        assertThrows(IllegalStateException.class, () -> service.forPlugin(beforeReload));
        Plugin afterReload = plugin("A", new AtomicBoolean(true));
        assertNotSame(old, service.forPlugin(afterReload));
        assertTrue(opened.get(0).closed);
        assertFalse(opened.get(1).closed);
        service.close();
    }

    private static Plugin plugin(String name, AtomicBoolean enabled) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getName")) return name;
                    if (method.getName().equals("isEnabled")) return enabled.get();
                    if (method.getName().equals("toString")) return name;
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (method.getName().equals("equals")) return proxy == arguments[0];
                    return null;
                });
    }

    private static final class Scope implements Messaging {
        final Executor executor;
        boolean closed;
        Scope(Executor executor) { this.executor = executor; }
        public MessageChannel channel(String name) { throw new UnsupportedOperationException(); }
        public void close() { closed = true; }
    }
}

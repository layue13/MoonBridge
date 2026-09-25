package dev.strataproxy.core.session;

import io.netty.resolver.AbstractAddressResolver;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Lets session tests hold hostname resolution while their I/O event loop stays live. */
final class DeferredBackendResolver extends AddressResolverGroup<InetSocketAddress> {
    private final String deferredHost;
    private final CompletableFuture<Pending> pending = new CompletableFuture<>();

    DeferredBackendResolver(String deferredHost) {
        this.deferredHost = deferredHost;
    }

    CompletableFuture<Pending> pending() { return pending; }

    @Override
    protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
        return new AbstractAddressResolver<>(executor, InetSocketAddress.class) {
            @Override protected boolean doIsResolved(InetSocketAddress address) {
                return !address.isUnresolved();
            }

            @Override protected void doResolve(InetSocketAddress address, Promise<InetSocketAddress> promise) {
                if (address.getHostString().equals(deferredHost)) {
                    pending.complete(new Pending(executor, promise, address.getPort()));
                } else if (address.getHostString().equals("127.0.0.1")) {
                    promise.setSuccess(new InetSocketAddress(InetAddress.getLoopbackAddress(), address.getPort()));
                } else {
                    promise.setFailure(new IllegalArgumentException("unexpected hostname " + address.getHostString()));
                }
            }

            @Override protected void doResolveAll(InetSocketAddress address,
                                                  Promise<List<InetSocketAddress>> promise) {
                promise.setFailure(new UnsupportedOperationException("test resolves one address"));
            }
        };
    }

    record Pending(EventExecutor executor, Promise<InetSocketAddress> promise, int port) {
        void release() {
            promise.trySuccess(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        }
    }
}

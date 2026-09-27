package dev.strataproxy.messaging;

import java.util.Objects;

/** A logical messaging node. Backend endpoints are addressed by proxy backend name. */
public final class Endpoint {
    private static final Endpoint PROXY = new Endpoint(true, null);

    private final boolean proxy;
    private final String backendName;

    private Endpoint(boolean proxy, String backendName) {
        this.proxy = proxy;
        this.backendName = backendName;
    }

    public static Endpoint proxy() {
        return PROXY;
    }

    public static Endpoint backend(String backendName) {
        Objects.requireNonNull(backendName, "backendName");
        if (backendName.trim().isEmpty()) {
            throw new IllegalArgumentException("backendName must not be blank");
        }
        return new Endpoint(false, backendName);
    }

    public boolean isProxy() {
        return proxy;
    }

    /** Returns {@code null} for the proxy endpoint. */
    public String backendName() {
        return backendName;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Endpoint)) return false;
        Endpoint endpoint = (Endpoint) other;
        return proxy == endpoint.proxy && Objects.equals(backendName, endpoint.backendName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(proxy, backendName);
    }

    @Override
    public String toString() {
        return proxy ? "proxy" : "backend(" + backendName + ")";
    }
}

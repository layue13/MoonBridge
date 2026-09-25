package dev.strataproxy.api;

import java.util.Objects;
import java.util.Optional;

/** Result of the proxy's protocol transition; it does not assert backend plugin readiness. */
public record TransferResult(TransferStatus status, Optional<String> detail) {
    public TransferResult {
        Objects.requireNonNull(status, "status");
        detail = Objects.requireNonNull(detail, "detail");
    }

    public static TransferResult of(TransferStatus status) {
        return new TransferResult(status, Optional.empty());
    }

    public static TransferResult failed(String detail) {
        return new TransferResult(TransferStatus.FAILED, Optional.of(Objects.requireNonNull(detail, "detail")));
    }
}

package dev.strataproxy.api;

import java.util.Objects;
import java.util.Optional;

/** Result at the proxy network layer; it does not assert backend game readiness. */
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

package dev.strataproxy.backend.api;

/** Result of handing a plugin message to a backend-agent platform adapter. */
public final class BackendMessageResult {
    private final boolean accepted;
    private final String outcome;

    public BackendMessageResult(boolean accepted, String outcome) {
        this.accepted = accepted;
        this.outcome = outcome == null ? "failed" : outcome;
    }

    public boolean accepted() { return accepted; }
    public String outcome() { return outcome; }

    public static BackendMessageResult acceptedForWrite() { return new BackendMessageResult(true, "accepted_for_write"); }
    public static BackendMessageResult failure(String outcome) { return new BackendMessageResult(false, outcome); }
}

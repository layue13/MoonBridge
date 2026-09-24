package dev.strataproxy.backend.api;

/** Result of accepting a publication into the backend-agent channel broker. */
public final class BackendMessageResult {
    private final boolean accepted;
    private final String outcome;
    private final String messageId;

    public BackendMessageResult(boolean accepted, String outcome, String messageId) {
        this.accepted = accepted;
        this.outcome = outcome == null ? "failed" : outcome;
        this.messageId = messageId == null ? "" : messageId;
    }

    public boolean accepted() { return accepted; }
    public String outcome() { return outcome; }
    public String messageId() { return messageId; }

    public static BackendMessageResult accepted(String messageId) { return new BackendMessageResult(true, "accepted", messageId); }
    public static BackendMessageResult failure(String outcome) { return new BackendMessageResult(false, outcome, ""); }
}

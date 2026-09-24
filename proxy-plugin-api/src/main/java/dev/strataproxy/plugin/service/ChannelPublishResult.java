package dev.strataproxy.plugin.service;

/** Result of accepting a message into the proxy channel broker. */
public record ChannelPublishResult(boolean accepted, String outcome, String messageId) {
    public static ChannelPublishResult accepted(String messageId) { return new ChannelPublishResult(true, "accepted", messageId); }
    public static ChannelPublishResult failure(String outcome) { return new ChannelPublishResult(false, outcome == null ? "failed" : outcome, ""); }
}

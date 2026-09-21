package dev.strataproxy.plugin.service;

/** Result of handing a plugin-message packet to the proxy transport. */
public record PluginMessageResult(boolean accepted, String outcome) {
    public static PluginMessageResult acceptedForWrite() { return new PluginMessageResult(true, "accepted_for_write"); }
    public static PluginMessageResult failure(String outcome) { return new PluginMessageResult(false, outcome == null ? "failed" : outcome); }
}

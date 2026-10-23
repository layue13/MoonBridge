package dev.moonbridge.core.log;

/** What happened on the proxy in the last ten minutes, for the admin console. */
public record RecentActivity(long joins, long leaves, long refused, long moves, long failedMoves, long overloads) { }

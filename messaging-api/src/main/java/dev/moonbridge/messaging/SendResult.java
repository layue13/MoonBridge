package dev.moonbridge.messaging;

/**
 * Delivery status reported for one event destination. These are transport/queue observations, not proof
 * that application code did or did not execute: a timeout or connection loss can occur after delivery.
 */
public enum SendResult {
    /** The destination accepted the event into its bounded processing queue. */
    ACCEPTED,
    /** The destination is reachable but has no local event subscriber for this channel. */
    NO_SUBSCRIBER,
    /** The destination was not connected when routing was attempted. */
    NOT_CONNECTED,
    /** The destination could not accept the event because a bounded queue was full. */
    BACKPRESSURED,
    /** The destination or proxy rejected the event, for example due to policy or protocol validation. */
    REJECTED,
    /** No acceptance receipt arrived before the deadline; the event may still have been delivered. */
    TIMED_OUT,
    /** Delivery failed for another reason; application execution may be unknown. */
    FAILED
}

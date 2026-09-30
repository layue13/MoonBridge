# Transfer coordination

## Contract

Transfer coordination is optional. Without `TransferPreparingEvent` subscribers, transfers use the existing parallel login and cutover path. Ordinary PLAY forwarding does not dispatch or inspect transfer events.

After the destination TCP connection succeeds, before any destination Minecraft login bytes are written, the proxy calls the selected preparation listeners off its I/O thread. The source remains connected and usable. Listeners return `allow()`, `deny(reason)`, or `releaseSource(handler)`. Denial stops the transfer while preserving the source. All listeners are considered in registration order until denial; an allow cannot erase an earlier source-release requirement.

Preparation should validate and record reversible intent. It must not take a final snapshot or clear live player state: the source can still process gameplay. A plugin that records intent must reconcile abandoned intent independently; notification delivery is not a persistence guarantee.

If any listener requests source release, the proxy stops forwarding new source-world operations, drains already accepted network writes, detaches the source relay, and closes the source connection. It then invokes only the callbacks attached to the selected source-release decisions, in registration order, off the I/O thread. `SourceReleasedEvent` is callback context, not an independently subscribable notification. Socket closure does not mean the backend has processed logout or committed a save. Each required callback defines and confirms its own durable boundary through successful asynchronous completion.

Only after all required callbacks succeed does the proxy send the destination Handshake and LoginStart. It checks that the attempt, frontend connection, destination connection and destination registration generation are still current. Source-world packets received during the gap are discarded, never queued for replay into the destination. Existing Forge negotiation continues to permit control traffic and reject gameplay until its world boundary is ready.

## Identity and lifetime

`TransferContext` is an immutable snapshot containing a unique transfer ID, exact player/frontend connection identity, source and destination server snapshots, catalog registration generations, authenticated backend instance epochs (zero for static backends without an instance binding), and a total deadline. It exposes no channels, buffers, mutable sessions, database concepts or player inventory logic.

The listener cohort is selected once per attempt. Every required source-release callback remains bound to its original registration and plugin owner. Revocation, plugin shutdown, null results, exception, timeout or overload fails the transfer; it cannot silently shrink the required cohort. Late completions cannot resume a closed or replaced attempt.

Callbacks use a separate bounded dispatcher: two workers, 128 queued tasks and at most 128 pending events. Callback stages do not block I/O threads or globally serialize unrelated transfers. No subscribers means no transfer event, event future or event timer allocation. The dispatcher is created only when subscriptions exist. A slow plugin can cause its transfer to fail; it cannot create an unbounded queue.

## Deadlines and failures

Preparation and source-release confirmation each use the configured event deadline, including queue wait. Destination login gets its own 15-second budget after login starts for coordinated transfers. TCP connection retains its five-second budget. The existing cutover and Forge negotiation budgets remain. A total deadline bounds the entire coordinated attempt.

| Failure point | Outcome |
| --- | --- |
| TCP failure, preparation denial/error/timeout | Close candidate; source continues |
| Pause failure or partial detach | Preserve source only if its relay can be safely restored; otherwise disconnect |
| Source released, callback error/timeout or destination failure | Disconnect frontend; never fall back to the closed source |
| Destination registration changed | Fail this attempt; never authenticate against a replacement instance implicitly |
| Frontend disconnect or proxy shutdown | Close candidate and release local resources; ignore late results |

This opt-in sequence sacrifices the normal destination-login-before-source-close fallback window. TCP reachability does not prove whitelist, authentication, mod negotiation or gameplay readiness. `TransferResult.NETWORK_READY` retains its existing meaning; it does not certify persistence by itself.

## Acceptance

Correctness tests must establish source close before confirmation, confirmation before destination login, denial preserving the source, failure after release disconnecting, old source EOF not closing the waiting frontend, cancellation and stale callbacks, exact backend generation checks, and no old-world gameplay replay. Existing vanilla and Forge transfers must remain covered.

Measure ordinary PLAY forwarding against main `876de1a056a79b807a03f3877cbe6414b60e86dc`, with initial and post-transfer sessions, windows 1/16 and five repetitions. Also measure transfer throughput and p50/p99 latency at 0/1/4 participants and 1/16/64 concurrent players. Record environment, errors, allocation and retained buffering where measurable. An initial 3% default-path regression budget is a decision threshold, not a result; if measurement noise exceeds it, classify the comparison as inconclusive and improve the experiment. Passing functional tests does not establish performance superiority.

PlayerDataSQL integration is a separate plugin concern. These events do not implement SQL ownership, backend final-save confirmation, authoritative data loading, mod synchronization or crash recovery.

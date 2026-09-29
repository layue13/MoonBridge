# MoonBridge player-profile handoff contract

This document records the proxy and Bukkit host pieces added for PlayerDataSQL issue #16. It is an integration contract, not evidence that a complete profile store or Uranium admission hook is deployed.

## Proxy plugin SPI

The proxy plugin artifact exposes `dev.moonbridge.api.profile` through `PluginContext.profileHandoffs()`:

PDS proxy modules should compile against `uk.potatolab.moonbridge:proxy-plugin-api:<releaseVersion>`. Uranium hooks should compile against `uk.potatolab.moonbridge:backend-bukkit-api:<releaseVersion>` (Java 8 API surface).

```java
void register(ProfileHandoffCoordinator coordinator);
CompletionStage<ProfileState> queryActive(PlayerIdentity identity);

CompletionStage<PreparedProfile> prepareAdmission(ProfileHandoffRequest request);
CompletionStage<PreparedProfile> prepareTransfer(ProfileHandoffRequest request,
                                                   SourceInputBarrier proxyBarrier);
CompletionStage<ProfileState> queryActive(ProfileSession session);
```

Registering one coordinator enables required, fail-closed profile routing. A second coordinator is rejected. If the registered provider is unavailable or returns an exception, timeout, null stage/result, or stale request, MoonBridge does not send backend login bytes. Without a registered coordinator, the legacy routing path remains enabled; operators must load the profile coordinator to enable the feature.

`ProfileHandoffRequest` binds `PlayerIdentity` (UUID and monotonically allocated connection ID), username, proxy epoch, stable logical operation ID, per-route attempt ID, and the target's exact backend name, catalog generation, owner ID, and owner/node epoch. Transfers also include the exact source registration. Initial admission runs once per candidate before connecting. Cross-backend transfers pause client input and wait for profile preparation before dialing the target.

The profile provider should use the authenticated `context.messaging().channel(...).request(Endpoint.backend(name), payload, timeout)` path for backend control messages. It must define idempotent typed requests and replies for source freeze/barrier acknowledgement, final snapshot commit, target reservation, activation, and cancellation. `PreparedProfile.routed()` notifies the provider after successful network routing. `PreparedProfile.abort()` may complete successfully only after the profile authority confirms rollback is safe for the source epoch. MoonBridge resumes the source relay only after that successful response. An unknown or failed abort keeps the client isolated and closes the proxy session.

## Input barrier boundary

`SourceInputBarrier.proxyIngressStopped()` completes after MoonBridge disables client-to-source reads and waits for writes already accepted by that relay direction. It does not prove the source game thread applied those packets, drain work queued inside Bukkit/Forge, or cover arbitrary mod packet handlers. The provider must obtain a separate source server barrier acknowledgement before final snapshot capture and must reject the transfer if it cannot establish one. No in-flight mod packet drain guarantee is made by this proxy change.

The opposite backend-to-client direction remains live while profile preparation runs. Existing transfer cutover pauses both relay directions after the target candidate reports login/Forge readiness. A failed candidate uses the provider's `abort()` result before the proxy resumes the old route.

## Read-only active query

`ProfileHandoffs.queryActive(PlayerIdentity)` resolves the exact currently connected identity and current backend registration. The coordinator receives a `ProfileSession` containing that player identity, proxy epoch, and exact backend registration. The proxy rechecks that the connection and registration are still current when the asynchronous answer returns; a stale or unavailable answer becomes `ProfileState.UNKNOWN`. AetherShard must continue an arrival only for `ACTIVE`; `INACTIVE`, `UNKNOWN`, timeout, or error must not advance `ARRIVED` or teleport the player.

## Early backend proof preflight

`BukkitSessionService.preflight(String forwardedProofToken, UUID expectedPlayerId)` verifies the forwarding proof against this backend host's current backend name, backend epoch, proxy epoch, expected player UUID, expiry, signature, and one-time nonce. A valid proof is consumed once and cached until the matching Bukkit `PlayerJoinEvent`, where the host strips the transport property and binds the same returned `BackendPlayerSession` object. A repeated preflight or replay at Join cannot bind again. The cache expires with the proof and is rejected if backend or proxy epoch changes.

This permits an early Uranium login hook to authenticate the proxy identity before local player-file reads. The hook still must invoke it at the pre-file-read point, use the exact forwarded property, and gate entity construction/tick/input until profile restore and activation complete. MoonBridge's Bukkit join listener alone remains too late for that guarantee.

## Remaining integration and verification

- A PDS proxy plugin must implement `ProfileHandoffCoordinator` and serialize/authenticate the profile control protocol over the existing backend channel.
- PDS backend handlers must establish the server-side input barrier and issue target tickets bound to the request tuple.
- Uranium must call `BukkitSessionService.preflight` before local profile reads and activate only after required model restoration.
- AetherShard must gate final arrival on `queryActive(identity) == ACTIVE` in addition to its world lease.
- The proxy barrier only drains its own accepted socket writes; backend packet processing order, Forge/mod handler behavior, and actual runtime admission timing remain unverified.

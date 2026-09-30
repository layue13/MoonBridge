# Early backend session authentication

Verified on 2026-10-01 against the local `feature/early-backend-session` sources. This is an optional public Bukkit API addition; it does not modify transfer commands or require a PlayerDataSQL-specific core integration.

## Source checks

`gradlew --no-daemon check :proxy-core:installDist` succeeded. The seven project test suites contain 314 tests, zero failures, errors or skips; unchanged suites were up-to-date. `verifyApiJar` and `verifyHostJar` confirmed the Java 8 API/host packaging.

Lifecycle tests exercise repeated early authentication, proof replay, same-object Join promotion, denied login cleanup, expiry, stale Quit, epoch changes before Join and after promotion, and close.

## Real Uranium round trip

Command (Java 25 for proxy/tools, Java 8 for the server and Bukkit probe):

```powershell
./smoke/local-uranium-transfer.ps1 -BundlePath build/uranium-event-bundle `
  -InstalledPlugin -ReleaseSource -ReturnToOld -DebugSession `
  -AuthenticateEarly -BukkitApiJar <spigot-api-1.8-R0.1-SNAPSHOT.jar>
```

The probe compiles against public API artifacts. Each isolated backend installs one shared MoonBridge host, enables legacy forwarding and dynamically registers through the existing channel. It authenticates twice during PlayerLogin, checks identical binding and provisional-only status, verifies the private profile property was removed, then checks the same binding through `find` on the first tick after Join.

Successful run: `build/local-uranium-transfer-df0f9afbb80e471d9c725fe80cb5d712`.

- Two old-backend and one new-backend early authentication markers.
- Matching counts of online promotion markers; zero failure markers.
- Initial transfer and return both `NETWORK_READY`; synthetic Forge client observed round-trip respawns/keepalives.
- Optional asynchronous source-release barrier passed. **No database persistence is certified by this smoke.**
- Both servers stopped through their normal `stop` command; this script terminates only proxy/tap processes it created.

Server SHA-256: `A05ECB7CA50CB00390A690608CC243738711C2EC079C37FBA3B0119BB261373A`.
Shared host SHA-256: `FA7F990AF4C05DD008F6A25956CF175645F6E6B7F20DC253CFC7ACA100177569`.

The first new-harness run omitted `spigot.yml` legacy forwarding and was rejected with no early proof. The harness now explicitly enables it; rejection was not bypassed or weakened.

This is login lifecycle evidence on a minimal Forge server. It does not establish full-pack player-data restoration, module synchronization, or Blood Magic ledger performance. No PLAY throughput superiority claim is made for this change; authentication operates during login, not on every PLAY packet.

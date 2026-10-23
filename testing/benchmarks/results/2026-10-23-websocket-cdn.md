# WebSocket behind a CDN with modpack-shaped traffic: baseline, BoringSSL, keepalive — 2026-10-23

Base revision: `6224d3d` (main). This record covers the proxy half of the work: a modpack-shaped benchmark workload, the cost of `wss://` on the proxy, and a keepalive ping for CDN idle limits. The client-mod half (TCP_NODELAY, its own WebSocket codec, per-tick coalescing) has its own record.

**Outcomes:**
- `--profile modded` and `--websocket-tls 1` added to `ProxySessionBenchmark`.
- **BoringSSL adopted** for `wss://` listeners: `netty-tcnative-boringssl-static` 2.0.84.Final is a runtime dependency, and Netty uses it whenever it loads.
- **WebSocket keepalive adopted in its flag form.** The first form, Netty's `IdleStateHandler`, failed its rule and was replaced.

## Benchmark modes added

`--profile modded` replaces the fixed-size echo with fixed-seed traffic shaped like a large 1.7.10 pack:
- **Client tick:** each roundtrip is one tick of 3 serverbound packets: a C17 mod custom payload, a C04 position packet and a C03 on-ground packet.
- **Server answer:** the backend replies with that tick's clientbound mix, on average 10.2 packets and 6.1 KB over 256 templates:
  - 6–14 small packets: S3F custom payloads on mod channels, and S35 tile entity updates of 20–600 bytes;
  - a 2–8 KiB payload on about one tick in three;
  - a 30–60 KiB S21 chunk on about one in twenty.
- **Login burst:** after each Position and Look, the backend sends 70 × 30,000-byte S3F payloads (2.1 MB), standing in for Forge registry sync. Its wall time is reported as `login_burst_ms` and not counted in the timed phase.
- **Validation:** every clientbound packet carries the tick sequence and is checked byte for byte.
- **Counting:** frames and CPU per frame count both directions.

Client sending modes:
- **Default:** the client writes and flushes each packet on its own, which over WebSocket is one message per packet, as the released client mod sends.
- **`--client-coalesce 1`:** one write per tick.

`--websocket-tls 1` serves `wss://`:
- the certificate is a throwaway ECDSA P-256 one from the JDK's `keytool`;
- the proxy uses whichever `SslProvider` is available;
- the client is a JDK `SSLSocket`;
- `-Dio.netty.handler.ssl.noOpenSsl=true` forces the proxy onto the JDK provider.

## Environment and method

- **Hardware and software:** Apple M4 (10 cores), macOS, Zulu OpenJDK 25.0.4.1, Netty 4.2.18.Final, kqueue, loopback.
- **Settings:** `-Xms512m -Xmx512m`, `--connections 4 --messages 5000 --warmup 500 --repeats 2`, at `--window 1` and `--window 8`.
- **Ordering:** variant order rotated across JVM runs.
- **Errors:** every phase in every run below reports `errors=0`.

**Host load.**
- The host was not quiet. `appstoreagent` held about one core for the whole session.
- From about 16:15, separate Uranium Gradle integration runs and another session's builds pushed the load average to 5–7 intermittently.
- Two runs were discarded for this (raw output kept, sections 2 and 4). Decisions rest on the clean run, on per-pair ratios and on JFR attribution, as noted below.

## Baseline (clean run)

Median and [min–max] of 6 proxy phases each. Run 1 started at 16:09, before the external load. Its plain-TCP rows are omitted. In that run the modded TCP client read its input unbuffered, one syscall per VarInt byte, so TCP was limited by the client. That was fixed for later runs; the WebSocket client was already buffered.

| Window | Variant | Proxy frames/s | p50 ms | Proxy I/O CPU ns/frame | Login burst ms |
| --- | --- | ---: | ---: | ---: | ---: |
| 1 | ws | 544640 [439103–665242] | 0.093 [0.074–0.115] | 3444 [2668–4709] | 7.2 [2.5–12.1] |
| 1 | ws, client coalesces per tick | 605357 [490959–726909] | 0.081 [0.065–0.099] | 2948 [2266–3612] | 7.1 [2.5–11.6] |
| 1 | wss (JDK TLS) | 359592 [290906–465581] | 0.137 [0.103–0.159] | 6008 [4217–7529] | 30.2 [5.2–67.0] |
| 8 | ws | 915171 [825003–1111809] | 0.375 [0.307–0.444] | 2831 [2725–3208] | 7.3 [3.2–12.1] |
| 8 | ws, client coalesces per tick | 1266544 [926420–1426449] | 0.290 [0.264–0.364] | 2480 [2099–2989] | 7.1 [3.8–11.3] |
| 8 | wss (JDK TLS) | 513764 [401873–622225] | 0.696 [0.574–0.922] | 6142 [4815–7799] | 42.1 [6.1–77.0] |

- **Client coalescing:** sending a tick as one message instead of three saves 12–14% of proxy CPU per frame. That is the proxy's share of the client-mod change.
- **wss with the JDK provider** costs about twice the proxy CPU of plain ws.

A JFR `profile` recording at window 8 attributes each `moonbridge-session-io` CPU sample to the first component found walking down from the top frame (section 5 of the raw file):

| Component | ws | wss (JDK TLS) |
| --- | ---: | ---: |
| TLS (SSLEngine, SslHandler, JDK crypto) | — | 54.9% |
| WebSocket codec | 31.4% | 14.4% |
| MoonBridge handlers | 24.8% | 11.8% |
| Minecraft frame decode | 13.7% | 7.1% |
| Socket syscalls | 18.3% | 4.8% |
| Other Netty | 11.8% | 7.1% |
| Samples | 153 | 397 |

## BoringSSL for wss

**Decision rule (from the plan):**
- Try OpenSSL only if TLS is the dominant per-frame cost under `modded` wss. It is: 55% of samples.
- Adopt only if wss proxy CPU per frame is at least 20% lower, with no drop in frames/s.

**Method:**
- Same candidate jar for both variants, so only the provider differs. JDK was forced with `-Dio.netty.handler.ssl.noOpenSsl=true`, and the benchmark header confirmed `SslProvider JDK`.
- Four alternating pairs (8 phases per cell).

| Window | Provider | Proxy frames/s | p50 ms | Proxy I/O CPU ns/frame | Login burst ms |
| --- | --- | ---: | ---: | ---: | ---: |
| 1 | JDK | 311306 [253689–444768] | 0.144 [0.110–0.176] | 6343 [4389–8550] | 33.3 [6.2–72.7] |
| 1 | OpenSSL | 406447 [319477–502772] | 0.126 [0.094–0.151] | 4954 [3604–6201] | 23.8 [5.9–56.7] |
| 8 | JDK | 487782 [360427–682859] | 0.752 [0.568–0.967] | 6269 [4611–8411] | 38.6 [5.7–143.2] |
| 8 | OpenSSL | 652996 [338809–768243] | 0.552 [0.437–0.699] | 4009 [3549–5061] | 27.1 [4.2–108.1] |

Per-pair CPU ratios (OpenSSL / JDK):
- window 1: 0.83, 0.73, 0.72, 0.72;
- window 8: 0.80, 0.55, 0.65, 0.63.

**Verdict:** the median is 22% lower at window 1 and 36% lower at window 8. Every pair is lower, and frames/s rose 31% and 34%. **Adopted.**

**Cost of adopting:**
- The fat jar grows from 10.4 MB to 16.5 MB, with natives for linux-x86_64/aarch_64, osx-x86_64/aarch_64 and windows-x86_64.
- Other platforms fall back to the JDK provider. The startup line `Listening for WebSocket (TLS via OPENSSL|JDK)` says which is in use.
- `WebSocketListenerTest` now checks that the loaded context is OpenSSL, and that a TLS 1.2-only client, which is what the client mod offers, completes against it.

## WebSocket keepalive

The change: after the upgrade, the proxy sends an empty Ping when it has written nothing for `websocket.pingIntervalSeconds` (default 30, 0 disables). That keeps CDN and load-balancer idle timers, typically 60–100 s, from cutting quiet stretches: long modded logins, placement waits, stalled backends.

This is a correctness change, but it sits on the write path, so it had its own rule: proxy CPU per frame on ws at most 5% above main.

**First form: Netty's `IdleStateHandler(writerIdle)`.** It adds a listener to every outbound message. Measured in the same four pairs as the OpenSSL comparison:

| Window | Variant | Proxy frames/s | Proxy I/O CPU ns/frame |
| --- | --- | ---: | ---: |
| 1 | main | 528314 [442540–614041] | 3623 [2920–4599] |
| 1 | IdleStateHandler | 519787 [431592–607227] | 3696 [2972–4604] |
| 8 | main | 932088 [521373–1065588] | 2855 [2367–4061] |
| 8 | IdleStateHandler | 847474 [544615–1067703] | 3073 [2126–3780] |

- Per-pair ratios: window 1: 1.06, 1.03, 1.05, 0.93; window 8: 1.16, 1.08, 1.06, 0.83.
- At window 8 the median is +7.6%, so the rule fails. **Rejected.**

**Second form, adopted: `WebSocketKeepalive`.**
- **Design:** `write` only sets a flag. A task every half interval pings if the last half interval had no write, and counts its own ping as that period's write.
- **Bounds:** the proxy is then never silent for longer than the interval, and never pings after less than half of it.
- **Throughput comparison discarded:** a six-pair run happened under the external load. Per-pair ratios ranged 0.67–1.56 and the medians moved in opposite directions at the two windows (section 4 of the raw file).
- **Evidence instead, from JFR** at window 8, ws, both candidates on the same host minutes apart. These are the samples whose stack reaches the keepalive handler before handing off to the next handler (section 6):

| Candidate | CPU samples in the handler | Allocation samples in the handler |
| --- | ---: | ---: |
| IdleStateHandler | 20 / 154 (13.0%) | 6 / 100, 15.0 of 517 MB sampled weight |
| WebSocketKeepalive (flag) | 1 / 141 (0.7%) | 0 / 100 |

These sample counts are small. They show where the first form's cost was (the per-write listener and its completion) and that the flag form has essentially none. They are not a precise overhead figure.

## Limits

- One desktop, loopback only, synthetic traffic from a fake backend. There was no CDN hop, no real RTT, no Nagle/delayed-ACK interplay (loopback ACKs at once), no real client mod and no real modpack.
- The workload's packet mix is an informed guess, not a capture from a pack.
- The host carried external load for much of the session. The OpenSSL verdict rests on consistent per-pair ratios; the keepalive verdict on JFR attribution.
- The JDK `SSLSocket` client is not the client mod's Netty 4.0.10 `SslHandler` (TLS 1.2).
- Single-host loopback numbers are never capacity claims.

Raw output: [2026-10-23-websocket-cdn-raw.txt](2026-10-23-websocket-cdn-raw.txt).

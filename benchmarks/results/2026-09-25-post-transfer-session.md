# Ordinary PLAY forwarding after a backend transfer

Run date: 2026-09-25 (Asia/Shanghai). Core revision: `91d2d016e1cb1783948b9cae61088af490a6d684`; this change added only the benchmark mode and its runner option. Environment: Windows 11, Zulu OpenJDK 25.0.4.1, PowerShell 7.6.5, local loopback.

Each run used the same JVM and synthetic protocol-5 echo client, four connections, 4,096-byte PLAY bodies, 1,000 validated warmup exchanges and 10,000 measured exchanges per connection, and five alternating direct/proxy rounds. Direct connections used the same fake target backend as proxy connections within each run. In `post-transfer` mode, proxy clients first entered a separate fake backend with player entity ID 42, requested transfer to the target with entity ID 99, verified two Respawns, a Position and Look packet, `NETWORK_READY`, and the new `PlayerView.currentServer`; then they warmed up and measured ordinary PLAY echoes. Direct clients logged into the target backend. Login, transfer, and warmup were excluded from timing. The backend implementation and PLAY payload were the same across modes; the post-transfer target used entity ID 99 so the proxy retained an active ID mapping.

Commands, each with `-Connections 4 -Messages 10000 -Warmup 1000 -Payload 4096 -Repeats 5`:

- `-Window 1 -Mode initial`: [raw output](2026-09-25-current-initial-window1.txt)
- `-Window 1 -Mode post-transfer`: [raw output](2026-09-25-current-post-transfer-window1.txt)
- `-Window 16 -Mode initial`: [raw output](2026-09-25-current-initial-window16.txt)
- `-Window 16 -Mode post-transfer`: [raw output](2026-09-25-current-post-transfer-window16.txt)

| Window | Mode | Direct throughput range, roundtrips/s | Proxy throughput range, roundtrips/s | Paired proxy/direct ratios, rounds 1–5 | Proxy p95 latency range |
| ---: | --- | ---: | ---: | --- | ---: |
| 1 | initial | 58,738.8–77,230.3 | 34,832.6–43,110.3 | 0.558, 0.727, 0.591, 0.595, 0.598 | 0.116–0.129 ms |
| 1 | post-transfer | 57,505.3–75,175.5 | 33,954.2–41,207.5 | 0.548, 0.690, 0.588, 0.590, 0.593 | 0.121–0.134 ms |
| 16 | initial | 97,125.4–129,242.9 | 88,461.4–104,091.8 | 0.735, 0.725, 0.805, 1.065, 1.048 | 0.692–1.141 ms |
| 16 | post-transfer | 92,711.1–128,059.8 | 83,748.5–103,859.0 | 0.736, 0.731, 0.835, 1.069, 1.105 | 0.691–1.284 ms |

Every phase reported 40,000/40,000 correct measured echoes and zero errors. The two post-transfer runs completed 40 successful proxy transfers in total. Results for initial and post-transfer modes used separate JVM invocations, and direct throughput drifted across rounds, especially with window 16. The similar ranges and ratios do not establish zero transfer-path overhead, but they do not show a stable regression large enough to justify changing the relay or entity-ID mapping code from this packet mix.

This is a synthetic loopback echo benchmark. Its ordinary packet has ID `0x03` and no entity ID, so it exercises the post-transfer inspection path without causing an entity-ID rewrite. It does not represent target Forge modpack traffic, actual client behavior, cross-host networking, or production capacity.

# WebSocket: one binary message per flush instead of per frame — 2026-10-22

Base revision: `0253130` (main). Candidate: `WebSocketFrameAdapter` gathers every `ByteBuf` written before a flush in a Netty `CoalescingBufferQueue` and writes them as one `BinaryWebSocketFrame` of at most 64 KiB. Before, each Minecraft frame the relay wrote became its own binary message. Netty's `WebSocket08FrameEncoder` allocates a new buffer and copies header and payload for every message under 1 KiB (`GATHERING_WRITE_THRESHOLD = 1024`), and most PLAY packets are smaller than that. The relay already flushes once per read batch, so the change adds no waiting; each write's promise completes when the message carrying it is written. Inbound handling is unchanged. The plain TCP path does not pass through this handler.

**Outcome: adopted.**

## Decision rule (set before the comparison)

Adopt only if, over WebSocket, the burst32 median proxy I/O CPU per frame is at least 10% below the base and burst32 proxy frames/s is not lower. Window 1 and window 16 must show no consistent regression: CPU and p50 latency medians within +10%, or overlapping ranges.

## Benchmark mode added for this

`ProxySessionBenchmark --websocket 1` enables the proxy's WebSocket listener (plain `ws://`, path `/mc`, direct address policy). Proxy-phase clients upgrade over HTTP and then send one masked binary message per client flush, reading the server's messages back as one byte stream. Direct-phase clients still use TCP to the same fake backend, because that backend is a Minecraft TCP server. The proxy/direct ratio therefore includes the client's own WebSocket cost; proxy I/O CPU per frame is the metric for the proxy's WebSocket path.

## Environment and method

Apple M4 (10 cores), macOS, Zulu OpenJDK 25.0.4.1, Netty 4.2.18.Final, kqueue transport, loopback only. Both variants were built with `:proxy-core:installDist`, and the same benchmark source was compiled against each `moonbridge.jar`. For each workload, three base/candidate JVM pairs ran, alternating which variant started (base first in pairs 1 and 3). Each JVM ran `--repeats 2`. Window 16 was noisy in both variants, so three more pairs ran for it (candidate first in pairs 4 and 6), giving 12 phases per variant. Every echo was checked; all 96 phases report `errors=0`.

```sh
java -Xms512m -Xmx512m --enable-native-access=ALL-UNNAMED -cp "<variant>/classes:<variant>/moonbridge.jar" \
  dev.moonbridge.core.session.ProxySessionBenchmark --transport kqueue --websocket 1 \
  --connections 4 --messages 6000 --warmup 600 --payload 32 --window 4 --burst 32 --repeats 2    # burst32
  --connections 4 --messages 5000 --warmup 500 --payload 1024 --window 1 --burst 1 --repeats 2   # w1
  --connections 4 --messages 5000 --warmup 500 --payload 1024 --window 16 --burst 1 --repeats 2  # w16
```

## Results

Values are the median and [min–max] of the proxy phases (6 per cell; 12 for w16).

| Workload | Variant | Proxy frames/s | p50 ms | p95 ms | Proxy I/O CPU ns/frame |
| --- | --- | ---: | ---: | ---: | ---: |
| burst32 | base | 713394 [696623–738749] | 0.692 [0.676–0.717] | 0.968 [0.944–0.988] | 3240 [2931–3360] |
| burst32 | candidate | 734747 [713206–751184] | 0.673 [0.661–0.686] | 0.990 [0.974–1.021] | 2550 [2245–2805] |
| w1 | base | 49680 [45249–53075] | 0.075 [0.067–0.087] | 0.130 [0.121–0.139] | 36196 [32565–42238] |
| w1 | candidate | 49094 [44841–54748] | 0.075 [0.066–0.086] | 0.131 [0.119–0.144] | 36780 [31030–42779] |
| w16 (pairs 1–3) | base | 92391 [39253–119096] | 0.587 [0.447–1.408] | 1.237 [0.761–2.361] | 34938 [25992–38786] |
| w16 (pairs 1–3) | candidate | 63312 [39080–135702] | 0.946 [0.398–1.500] | 2.116 [0.892–2.746] | 32585 [22885–47882] |
| w16 (pairs 4–6) | base | 70553 [35263–125974] | 0.526 [0.246–0.855] | 1.634 [0.778–3.391] | 33664 [19957–45631] |
| w16 (pairs 4–6) | candidate | 107407 [88704–116627] | 0.498 [0.477–0.567] | 1.095 [0.869–1.376] | 27828 [22746–32842] |
| w16 (all 12) | base | 90596 [35263–125974] | 0.608 [0.447–1.880] | 1.237 [0.761–3.391] | 34642 [19957–45631] |
| w16 (all 12) | candidate | 98983 [39080–135702] | 0.550 [0.398–1.500] | 1.138 [0.869–2.746] | 28822 [22746–47882] |

Allocation, from one JFR `profile` recording per variant on burst32 (sampled weight on `moonbridge-session-io` threads): base 995 MB, candidate 846 MB. The base's per-frame `BinaryWebSocketFrame` (67 MB) is no longer among the candidate's top allocations. In both variants the largest items are the relay's per-frame `DefaultChannelPromise`, `ChannelOutboundBuffer$Entry` and decoder slices, which this change does not touch. The candidate's `StacklessClosedChannelException` sample comes from Netty's channel-close path at the end of each phase, not from the data path. Profiled runs measured 2138–2407 ns/frame for the base and 1597–1807 ns/frame for the candidate.

Raw output: [2026-10-22-websocket-coalescing-raw.txt](2026-10-22-websocket-coalescing-raw.txt) (lines are `workload variant pair phase: metrics`).

## Interpretation

- burst32: median CPU per frame is 21% lower and the ranges do not overlap; frames/s and p50 improved. The criteria are met.
- w1: one frame per read, so there is nothing to coalesce. Throughput, latency and CPU ranges overlap; no regression.
- w16: very noisy in both variants (base throughput alone spans 35k–126k frames/s). The first three pairs had a worse candidate p50 median with overlapping ranges; the next three, with the start order rotated, reversed every direction. Pooled, the candidate is no worse on any median. Coalescing cannot add waiting: unflushed writes were already held until the same flush.

## Limits

One loopback run on one desktop, plain `ws://` (no TLS, so SslHandler's own coalescing was not exercised), synthetic echo traffic, no CDN, no real client mod (whose Netty 4.0.10 decoder now receives fewer, larger messages; its limit is 16 MiB per frame, far above the 64 KiB cap), no cross-host RTT and no production player counts. Single-host loopback numbers are never capacity claims.

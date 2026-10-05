# Relay flush batching with burst traffic — 2026-10-05

Base revision: `e300160` (main). Candidate: the ordinary `RawRelay` path writes each decoded frame with `write` and flushes the peer once per inbound read batch, on `channelReadComplete`. A per-batch event-loop task flushes frames forwarded outside a socket read (for example a delayed tab-completion request). Replay of paused frames still uses `writeAndFlush`.

## Why re-test

The [2026-09-25 batching candidate](2026-09-25-batched-flush-candidate.md) was rejected because the echo benchmark showed no benefit. In that benchmark each client write carried one frame, so a socket read rarely held more than one frame and there was nothing to batch. Minecraft servers send many small PLAY packets per tick, such as entity movement, so one read can hold many frames. With `TCP_NODELAY`, the base relay then makes one flush, one write syscall and usually one TCP segment for each frame. `ProxySessionBenchmark` now has `--burst N`. Each client roundtrip writes N frames in one socket write, and the fake backend echoes them with one flush. The benchmark also reports proxy I/O-thread CPU per frame from `ThreadMXBean` and host-wide TCP `OutSegs` per frame from `/proc/net/snmp`.

## Decision criteria (set before the comparison)

Adopt the candidate if, on burst traffic, it lowers TCP segments per frame and proxy I/O CPU per frame with little or no range overlap. The existing one-frame workloads (window 1 and 16, 1 KiB) must show no consistent regression.

## Environment and method

Linux 6.18 container, 4 vCPU Intel Xeon, Temurin 25.0.4.1, Netty 4.2.2.Final, loopback only. The base and candidate distributions were installed separately and the same benchmark source was compiled against each. For each workload the script ran three base/candidate JVM pairs in alternation. Each JVM ran `--repeats 2`, so there are six paired direct/proxy phases per cell. Every echo was checked by sequence and payload. All runs had zero errors.

```sh
java -Xms512m -Xmx512m -cp "<variant>/classes:<variant>/lib/*" dev.moonbridge.core.session.ProxySessionBenchmark \
  --connections 4 --messages 3000 --warmup 300 --payload 32 --window 4 --burst 32 --repeats 2     # burst32
  --connections 4 --messages 5000 --warmup 500 --payload 1024 --window 1 --burst 1 --repeats 2    # w1
  --connections 4 --messages 5000 --warmup 500 --payload 1024 --window 16 --burst 1 --repeats 2   # w16
```

## Results

Values are the median and [min–max] of the six proxy phases. "Proxy/direct" divides each proxy phase's frames/s by its paired direct phase.

| Workload | Variant | Proxy/direct | p50 ms | p95 ms | Proxy I/O CPU ns/frame | TCP segments/frame |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| burst32 | base | 0.289 [0.211–0.467] | 0.760 [0.612–1.109] | 2.872 [1.817–4.658] | 4079 [3094–6352] | 1.379 [1.271–1.419] |
| burst32 | candidate | 0.442 [0.349–0.547] | 0.488 [0.349–0.702] | 2.189 [1.147–2.609] | 1827 [955–3164] | 0.117 [0.110–0.128] |
| w1 | base | 0.497 [0.451–0.674] | 0.071 [0.051–0.089] | 0.162 [0.116–0.197] | 32163 [22882–37248] | 7.002 [7.001–7.003] |
| w1 | candidate | 0.461 [0.306–0.589] | 0.057 [0.053–0.105] | 0.149 [0.121–0.240] | 33118 [30163–43753] | 7.002 [7.002–7.002] |
| w16 | base | 0.562 [0.296–0.606] | 0.464 [0.353–0.625] | 2.682 [1.305–5.422] | 17170 [11471–25711] | 6.112 [5.941–6.220] |
| w16 | candidate | 0.609 [0.414–0.942] | 0.438 [0.350–0.460] | 1.568 [0.998–3.798] | 13730 [9167–19031] | 4.414 [4.351–4.428] |

Raw output: [2026-10-05-relay-batched-flush-raw.txt](2026-10-05-relay-batched-flush-raw.txt).

## Interpretation

- Burst traffic: TCP segments per frame fell by about 92%, and the ranges do not overlap. The direct path's own floor in these runs was about 0.07. Median proxy I/O CPU per frame fell by about 55%, with only a marginal range overlap (3094 vs 3164 ns). Paired throughput and p50 latency improved. The criteria are met.
- Window 1: one frame per read, so batching cannot act. CPU, latency and throughput ranges overlap and segments are identical. There is no regression signal.
- Window 16: segments fell because frames that happen to arrive in the same read now share one flush. CPU, latency and throughput ranges overlap.

## Limits

This is a synthetic loopback echo on a shared 4-vCPU container, and the direct baseline drifted between runs. The segment counter is host-wide and includes ACKs. The run did not use real Forge traffic, encryption (`ONLINE_BUNGEE`), cross-host RTT or production player counts. It shows that per-frame flushing costs syscalls, segments and CPU on burst traffic. It does not give a production capacity figure. Measure on target hardware with representative pack traffic before claiming an end-to-end gain.

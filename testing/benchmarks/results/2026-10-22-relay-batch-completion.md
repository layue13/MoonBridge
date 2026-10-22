# Relay write completion per batch instead of per frame — 2026-10-22

Base revision: `4ed6162` (main). Candidate: [`2026-10-22-relay-batch-completion.patch`](2026-10-22-relay-batch-completion.patch). The ordinary `RawRelay` path still writes each frame with `peer.write`, but only the last write of each read batch gets a completion listener. The listener is attached when the batch is flushed and subtracts the whole batch from `writesInFlight`. Before, every frame got a listener, and every completion queued one more event-loop task. One channel completes its writes in order and fails every later write once one fails, so the last write's outcome covers the batch. The paused-replay path was unchanged.

**Outcome: not adopted.** Allocation on the proxy I/O threads fell by about a fifth, but per-frame CPU did not move beyond run-to-run range.

## Decision rule (set before the comparison)

Adopt only if, on burst32, the candidate's median proxy I/O CPU per frame is at least 10% below the base and the paired proxy/direct throughput is not lower. Window 1 and window 16 must show no consistent regression (medians within 10% or overlapping ranges).

## Profile that motivated it

A JFR `profile` recording of the base on burst32 attributed the sampled allocation on `moonbridge-session-io` threads as follows: `ChannelOutboundBuffer$Entry` 26.8%, `DefaultChannelPromise` 26.6%, `UnpooledSlicedByteBuf` (frame decoder slices) 20.8%, and two `RawRelay$Side` lambdas 24.2%. The lambdas were the per-frame completion listener and the task it queued. They were the only share the relay itself could remove without changing write semantics.

## Environment and method

Apple M4 (10 cores), macOS, Zulu OpenJDK 25.0.4.1, Netty 4.2.18.Final, kqueue transport, loopback only. Both variants were built with `:proxy-core:installDist`, and the same `ProxySessionBenchmark` source was compiled against each `moonbridge.jar`. For each workload, three base/candidate JVM pairs ran, alternating which variant started (base first in pairs 1 and 3). Each JVM ran `--repeats 2`, so there are six paired direct/proxy phases per cell. Every echo was checked; all 72 phases report `errors=0`.

```sh
java -Xms512m -Xmx512m --enable-native-access=ALL-UNNAMED -cp "<variant>/classes:<variant>/moonbridge.jar" \
  dev.moonbridge.core.session.ProxySessionBenchmark --transport kqueue \
  --connections 4 --messages 6000 --warmup 600 --payload 32 --window 4 --burst 32 --repeats 2    # burst32
  --connections 4 --messages 5000 --warmup 500 --payload 1024 --window 1 --burst 1 --repeats 2   # w1
  --connections 4 --messages 5000 --warmup 500 --payload 1024 --window 16 --burst 1 --repeats 2  # w16
```

## Results

Values are the median and [min–max] of the six proxy phases. "Proxy/direct" divides each proxy phase's frames/s by its paired direct phase.

| Workload | Variant | Proxy/direct | p50 ms | p95 ms | Proxy I/O CPU ns/frame |
| --- | --- | ---: | ---: | ---: | ---: |
| burst32 | base | 0.971 [0.917–1.004] | 1.321 [1.307–1.397] | 1.613 [1.573–1.691] | 2471 [2404–2678] |
| burst32 | candidate | 0.948 [0.924–0.986] | 1.355 [1.298–1.402] | 1.620 [1.561–1.713] | 2412 [2334–2538] |
| w1 | base | 0.507 [0.437–0.655] | 0.067 [0.062–0.080] | 0.123 [0.111–0.134] | 29640 [26286–35591] |
| w1 | candidate | 0.508 [0.443–0.521] | 0.070 [0.064–0.078] | 0.123 [0.116–0.161] | 30856 [27733–33267] |
| w16 | base | 0.811 [0.749–0.854] | 0.419 [0.404–0.519] | 1.150 [0.921–1.265] | 23695 [22665–24491] |
| w16 | candidate | 0.779 [0.742–0.799] | 0.486 [0.397–0.524] | 0.973 [0.803–1.100] | 22034 [18155–22810] |

Allocation, from one JFR `profile` recording per variant on burst32 (sampled weight on `moonbridge-session-io` threads):

| Variant | Total | Relay lambdas | Promise | Outbound entry | Slices |
| --- | ---: | ---: | ---: | ---: | ---: |
| base | 677 MB | 167 MB | 180 MB | 181 MB | 141 MB |
| candidate | 530 MB | 6 MB | 174 MB | 212 MB | 126 MB |

Raw output: [2026-10-22-relay-batch-completion-raw.txt](2026-10-22-relay-batch-completion-raw.txt) (lines are `workload variant pair phase: metrics`).

## Interpretation

- burst32: the median CPU per frame is 2.4% lower and the ranges overlap; paired throughput is slightly lower, also overlapping. The 10% CPU criterion is not met.
- The candidate removed 97% of the relay's own allocation and 22% of the I/O threads' total. JFR allocation samples are weighted estimates; the promise, entry and slice rows did not change in the code and differ only by sampling. On this heap, young-generation collection of short-lived objects is cheap enough that this does not show up as CPU per frame.
- w1 and w16: CPU and throughput ranges overlap. w16 p95 is lower, but p50 is higher, so there is no consistent direction either way.

## Limits

On this host the direct path runs at nearly the same rate as the proxy path (proxy/direct close to 1 on burst32). The single-JVM client and fake backend are the throughput limit, so throughput cannot show a proxy-side gain here; CPU per frame is the meaningful metric. This was one loopback run on one desktop, with no encryption (`ONLINE_BUNGEE`), WebSocket, real Forge traffic, cross-host RTT or production player counts. GC cost under a long-running, larger heap was not measured. A future run on Linux with epoll, or a GC-sensitive measurement (allocation rate under sustained load with many sessions), could revisit it with the patch above. Single-host loopback numbers are never capacity claims.

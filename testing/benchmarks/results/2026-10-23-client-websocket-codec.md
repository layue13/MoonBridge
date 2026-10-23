# Client mod: own WebSocket codec instead of Netty 4.0.10's — 2026-10-23

Base revision: `6224d3d` (main). The client mod used Minecraft's bundled Netty 4.0.10 codec: `WebSocket13FrameEncoder(true)` and `WebSocket13FrameDecoder(false, false, 16 MiB)`. Vanilla calls `writeAndFlush` once per packet, so every packet became its own masked binary message. That encoder writes a separate header buffer and a body copy masked byte by byte, with a mask from `Math.random()`. The decoder is a `ReplayingDecoder` that copies every payload.

Candidates (commit `1decc44`):
- **Encoder.** `ClientFrameEncoder` queues outbound buffers. A flush schedules one event-loop task per batch, so packets that the client thread queued in the meantime share one binary message of at most 64 KiB. Each message is one buffer: the header, then the payload masked 4 bytes at a time with a `ThreadLocalRandom` mask. Pongs skip the queue and are flushed at once.
- **Decoder.** `ClientFrameDecoder` emitted binary and continuation payloads as retained slices of each read, as soon as their bytes arrived, so nothing was copied. It is not a `ByteToMessageDecoder`: in 4.0.10 that class calls `discardSomeReadBytes()` on its cumulation before it fires the decoded messages, which would move bytes under slices it just handed out.

Also in this change, without a benchmark: ws/wss connections set `TCP_NODELAY` back to true after vanilla's initializer turned Nagle on. Plain TCP is unchanged.

**Outcome: encoder adopted, decoder rejected** (the client keeps Netty's `WebSocket13FrameDecoder`).

## Decision rule (set before the measured runs)

Encoder (`ClientFrameEncoder`) is kept only if all hold:
- median messages per 1000 packets is at most a third of the base median;
- median event-loop CPU ns/packet is not above the base median;
- a lone packet still leaves in the event-loop task right after its write (`ClientFrameEncoderTest.lonePacketLeavesInTheTaskRightAfterItsWrite`).

Decoder (`ClientFrameDecoder`) is kept only if, in both decode views (codec only, and codec plus vanilla's splitter), the median event-loop CPU ns/byte and the median allocated bytes/byte are each at least 25% below the base median, and the candidate's min–max range does not overlap the base's for either metric.

If the decoder misses and the encoder meets its rule, only the encoder is kept.

Order: six JVMs, base/candidate/candidate/base/base/candidate (the starting variant alternates per pair), each with one warm-up phase and three measured phases per mode. Results are the median and [min–max] of the 9 measured phases per variant.

The rule handed to this work asked only for codec-only decode numbers. The splitter view was added after one unrecorded screening run per variant. That run showed the candidate handing vanilla's splitter 14× more buffers (one per read instead of one per message). With the splitter behind it, total allocation came out higher than base. The decoder only changes what the splitter receives, so it has to win with the splitter included. This makes the decoder rule stricter, never looser. The verdict would be the same without it: on its own, the codec-only view misses the CPU criterion.

## Benchmark

`./gradlew codecBenchmark -PbenchArgs="--variant base|candidate --repeats 3 --label jvmN"` in `client-mod/` (`CodecBenchmark`, test sources, never packaged; the decode modes exist only in `1decc44`). Packet sizes, fixed seed: 88% 20–300 B, 11% 2–8 KiB, 1% 30–60 KiB.

- **Encode.** 100 000 packets (112 MiB of payload). A non-loop thread plays vanilla's client thread: one `writeAndFlush` per packet, in bursts of 1–24 packets, waiting for each burst's last write to complete. A sink at the pipeline head stands in for the socket. The base pipeline includes the old adapter's `ByteBuf` → `BinaryWebSocketFrame` wrapping. Messages are counted as frames entering Netty's encoder (base) or buffers leaving ours (candidate).
- **Decode.** The proxy's view of a session: a ~2 MiB login burst in 32 KiB frames, then 40 000 packets in messages of 1–32 packets each, capped at 64 KiB, framed as Minecraft frames (VarInt length + body). The 48 MiB stream is delivered in 1460-byte reads on the event loop, 8 passes per phase. "Decode + splitter" adds a copy of vanilla 1.7.10's `MessageDeserializer2` logic, which copies each packet out with `readBytes`.
- **Measurement.** CPU and allocation are the event-loop thread's own (`ThreadMXBean`); the producer thread's CPU is reported separately. All buffers come from an unpooled heap allocator, so the per-thread allocation counter sees ByteBuf memory too.

## Environment and method

Apple M4 (10 cores), macOS 27.0.1, Zulu OpenJDK 17.0.20.1 (the client-mod test toolchain), Netty 4.0.10.Final, `-Xms1g -Xmx1g -Dio.netty.leakDetectionLevel=disabled`. The host was not quiet: load average was 5.1–7.4 at the start of each JVM (logged in the raw file). Sources were a separate Gradle run using about 4 cores and `appstoreagent` using about 1. The proxy session's own benchmarks were paused while these ran. Every decode phase checked the decoded byte count (and, with the splitter, the packet count). The candidate's correctness is covered by the unit tests, not by the benchmark.

## Results

Median and [min–max] of 9 measured phases per variant. "Pair ratios" are the candidate median over the base median within each JVM pair (jvm1/2, jvm3/4, jvm5/6), which cancels slow drift in host load.

| Encode | Base | Candidate | Pair ratios |
| --- | ---: | ---: | --- |
| Messages per 1000 packets | 1000 [1000–1000] | 82.9 [82.8–83.7] | 0.083, 0.083, 0.083 |
| Flushes (socket writes) per 1000 packets | 1000 [1000–1000] | 80.0 [79.8–80.8] | 0.081, 0.080, 0.080 |
| Event-loop CPU ns/packet | 4667 [3833–8377] | 2101 [1785–3573] | 0.466, 0.443, 0.494 |
| Event-loop CPU ns/byte | 3.97 [3.26–7.13] | 1.79 [1.52–3.04] | same |
| Event-loop allocation B/packet | 1362 [1362–1362] | 1203 [1203–1203] | 0.883 ×3 |
| Producer-thread CPU ns/packet | 408 [322–1036] | 418 [344–830] | 1.094, 0.951, 1.127 |

| Decode | Base | Candidate | Pair ratios |
| --- | ---: | ---: | --- |
| Codec only: CPU ns/byte | 0.117 [0.095–0.170] | 0.096 [0.088–0.149] | 0.851, 0.821, 0.922 |
| Codec only: allocation B/byte | 1.006 [1.006–1.006] | 0.035 [0.035–0.035] | 0.035 ×3 |
| Codec only: buffers passed on per pass | 2670 | 37155 | 13.9 |
| With splitter: CPU ns/byte | 0.265 [0.232–0.412] | 0.320 [0.280–0.490] | 1.252, 1.136, 1.257 |
| With splitter: allocation B/byte | 2.544 [2.544–2.544] | 2.794 [2.794–2.794] | 1.098 ×3 |

Raw output: [2026-10-23-client-websocket-codec-raw.txt](2026-10-23-client-websocket-codec-raw.txt). Lines are `mode variant jvmN phase: metrics`; the first phase of each JVM is warm-up and excluded.

## Interpretation

- **Encoder: meets the rule.**
  - It sends 12× fewer messages (a third of base would be enough). Within a burst the client thread queues packets faster than the loop drains them, so a burst almost always becomes one message.
  - Event-loop CPU per packet is 51–56% lower in every pair. The per-message header buffer, the byte-wise mask and the per-packet promise and flush work are gone.
  - The lone-packet test passes, and so does a test where 20 packets queued from another thread while the loop is busy leave as one message.
  - Fewer messages also mean fewer socket writes (80 instead of 1000 per 1000 packets) and fewer TLS records on wss. This benchmark does not measure those, because its sink is not a socket.
- **Decoder: misses the rule.**
  - On its own it removes almost all of the codec's allocation, but CPU drops only 8–18% per pair, with overlapping ranges.
  - With vanilla's splitter behind it, it is worse on both metrics. The splitter now receives 1460-byte slices: it starts a cumulation from a slice whose capacity cannot grow, copies on the next read, and grows that copy again for every packet spanning several reads. The copy the codec saved reappears downstream, plus more.
  - Getting a real gain would mean handing the splitter whole messages without copying (for example a composite of slices). That is out of scope here.
- **Effect on the proxy (reported, not measured here).** The proxy session measured that one message per client tick instead of one per packet cuts proxy I/O CPU per frame by about 12–14% (`ProxySessionBenchmark --profile modded --websocket 1 --client-coalesce 1`). That record is `2026-10-23-websocket-cdn.md` in [layue13/MoonBridge#14](https://github.com/layue13/MoonBridge/pull/14).

## Limits

- **No real network.** One in-process microbenchmark on one desktop under background load. There is no socket, no real CDN, no real RTT and no TLS. The interplay of `TCP_NODELAY` with Nagle, delayed ACKs and the CDN's own buffering is not measured at all; that change rests on the argument that the encoder already batches.
- **Synthetic traffic.** The packet mix and burst pattern are made up, not taken from a real modpack session. Real coalescing depends on how vanilla and mods spread writes across a tick.
- **Smaller pieces not measured:**
  - promise completion: a write's promise completes when its message is written, which is slightly later than before;
  - vanilla's decrypt handler in online mode, which would sit between the codec and the splitter;
  - direct buffers.

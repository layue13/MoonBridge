# Event-loop task queue, Netty 4.2.18, allocator and leak detection — 2026-10-05

Environment and method as in the [batched-flush record](2026-10-05-relay-batched-flush.md): Linux 6.18 container, 4 vCPU Intel Xeon, Temurin 25.0.4.1, loopback. Each cell is the median [min–max] of ten proxy phases (five rotated JVM runs × `--repeats 2`). The variant start order rotated each run. All echoes were verified, zero errors. Raw output: [2026-10-05-event-loop-queue-and-netty-raw.txt](2026-10-05-event-loop-queue-and-netty-raw.txt) (`[exp1]` queue, `[exp2]` Netty/allocator, `[exp3]` leak detection).

## Profile that motivated this

A JFR CPU profile (`jdk.ExecutionSample` at 1 ms, session I/O threads only, burst32 workload, epoll) showed Java code, not syscalls, dominating: 1570 Java samples against about 130 in `writev`/`recv`. The hottest Java leaf frames were `Buffer.memoryAddress` (15–22%), the event-loop task-queue `offer` (about 9%), and leak-detection wrappers (about 15% on 4.2.18).

## 1. Regression found in the previous commit: event-loop task queue

Switching from the deprecated `NioEventLoopGroup` to `MultiThreadIoEventLoopGroup` silently changed the event loops' task queue from Netty's MPSC queue to a `LinkedBlockingQueue` (confirmed by reflection; the deprecated groups' loops use `MpscUnboundedAtomicArrayQueue`). The relay posts one write-completion task per frame. The earlier epoll-vs-NIO comparison could not see this because both variants used the new group. `NetworkTransport` now creates its loops with MPSC queues, and `NetworkTransportTest.eventLoopsQueueTasksInANonBlockingMpscQueue` fails against the regressed code.

| Workload | Variant | I/O CPU ns/frame | p95 ms | frames/s |
| --- | --- | ---: | ---: | ---: |
| burst32 | before transport change (old group) | 1527 [988–2781] | 1.433 | 688,619 |
| burst32 | regressed (LinkedBlockingQueue) | 1836 [961–4304] | 1.646 | 655,950 |
| burst32 | fixed (MPSC), NIO | 1228 [989–1889] | 1.173 | 750,644 |
| burst32 | fixed (MPSC), epoll | 1189 [918–2427] | 0.992 | 845,186 |

On burst traffic the regressed build had the highest median CPU per frame and p95. The fix is lower than both. Ranges overlap, so the effect is real in direction but modest in size. Window 1 and 16 show no difference.

## 2. Netty 4.2.2 → 4.2.18 (adopted)

Same code, epoll, burst32: I/O CPU 987 [781–1862] vs 1370 [870–1939] ns/frame, p95 0.875 vs 1.048 ms. Window 16 p95 1.020 vs 1.350 ms, window 1 equal. In a profile of the identical workload, `Buffer.memoryAddress` fell from 22.3% of Java samples to out of the top list, and total Java samples on the I/O threads dropped from 1460 to 836. Samples of the same work are comparable, which makes this the stronger evidence than the overlapping benchmark ranges.

## 3. Not adopted

- `-Dio.netty.allocator.type=pooled` (default is adaptive): burst32 CPU 1314 vs 1370 ns/frame on 4.2.2 and 1200 vs 987 on 4.2.18. No consistent difference.
- `-Dio.netty.leakDetection.level=disabled` (4.2.18, epoll): burst32 CPU 968 [764–2655] vs 1300 [830–2015] ns/frame, p95 0.818 vs 1.069 ms; window 1 and 16 overlap. The direction matches the profile, but the ranges overlap. It also removes Netty's leak warnings, which are the safety net for the hand-written retain/release in the transfer path, so it is left as an operator decision (see operations.md).
- Batching CFB8 encryption into one `update` call: the JDK call overhead is small (below). Cost is almost entirely per byte.

| Frame bytes | per-frame update ns | one update over 32 frames, ns/frame |
| ---: | ---: | ---: |
| 10 | 311 | 257 |
| 33 | 541 | 536 |
| 200 | 3199 | 3315 |
| 1500 | 24112 | 24655 |

That is about 16.4 ns per byte, about 58 MiB/s per stream. CFB8 encrypts each byte with a full AES block that depends on the previous byte, so a single stream cannot go faster on one core.

## Limits

Same as the other 2026-10-05 records: one loopback host with a shared 4 vCPU box, no Forge traffic, no online-mode encryption in the session benchmark (the cipher figures come from a separate micro-benchmark).

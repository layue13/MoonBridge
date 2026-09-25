# JFR screening of the session hot path

Run date: 2026-09-25 (Asia/Shanghai). Profiled revision: `d90ebf9` on Windows 11 Enterprise, AMD Ryzen 7 7800X3D, Zulu OpenJDK 25.0.4.1. The recording ran for 44 seconds with Java Flight Recorder's `profile` settings. The local JFR file is under ignored `benchmarks/build/`; it is not published because JFR includes process environment events.

Command, after compiling `benchmarks/ProxySessionBenchmark.java` and installing the distribution:

```powershell
java -XX:StartFlightRecording=filename=benchmarks/build/session-w1.jfr,settings=profile,dumponexit=true `
  -cp "benchmarks/build;proxy-core/build/install/strataproxy/lib/*" `
  dev.strataproxy.core.session.ProxySessionBenchmark `
  --connections 4 --messages 40000 --warmup 4000 --payload 4096 --repeats 5 --window 1
```

All direct and proxy echoes were correct with zero errors. The full recording contained 11,845 `jdk.ObjectAllocationSample` events and 167 `jdk.ExecutionSample` events. In the full recording, the benchmark client's `readFrame` byte-array allocation accounted for 82.97% of weighted allocation pressure. After filtering to `strataproxy-session-io-*` threads, 2,172 allocation samples remained, but no execution samples. Weighted I/O-thread allocation pressure was led by `DirectByteBuffer.duplicate` (34.01%) and `DirectByteBuffer.slice` (25.68%); `Integer.valueOf` (6.90%) sampled under the Windows selector's `WEPollSelectorImpl`.

These are sampled allocation-pressure shares, not exact allocated bytes or a per-packet cost. The recording combines direct and proxy phases, and JFR did not capture enough proxy I/O execution samples to locate a CPU bottleneck. It therefore does not justify changing the ordinary relay path. The earlier [unprofiled paired run](2026-09-25-session-after-lifecycle.md) remains the throughput reference for this revision family. Profile again with representative Forge traffic and a method that yields usable CPU samples before attributing a production bottleneck.

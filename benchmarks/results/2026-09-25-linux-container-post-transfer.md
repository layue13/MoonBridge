# Linux container post-transfer session benchmark

Run date: 2026-09-25 (Asia/Shanghai). Source revision: `6b2d12e9b0131708b05d4c5c9e83dd3f96cf04a2`. The benchmark used the installed distribution produced by `:proxy-core:installDist` from that revision. Host: Windows 11, AMD Ryzen 7 7800X3D. Container: Docker Desktop Linux, 16 exposed CPUs, 20.97 GB reported memory, `eclipse-temurin:25-jdk` image digest `sha256:97014c4b396021f9ddb7d592a7dbedb0c4e4215c29e03dc01c393558aefb71c2`, OpenJDK 25.0.4+7.

Each mode ran in a fresh container and JVM. Direct and proxy phases within a mode used the same JVM, fake TCP backend, client code, four concurrent clients, 4,096-byte synthetic PLAY body and in-flight window. Proxy clients transferred from `bench` to `replacement` before warmup; direct clients connected to the same `replacement` backend. Login, transfer and 1,000 warmup exchanges per client were excluded from timing. Direct/proxy phase order alternated across five rounds. Every timed echo was checked for packet ID, sequence, length and body.

| Window | Timed echoes per phase and round | Direct roundtrips/s range | Proxy roundtrips/s range | Paired proxy/direct ratios, rounds 1–5 | Proxy p95 range |
| ---: | ---: | ---: | ---: | --- | ---: |
| 1 | 40,000 | 44,424–47,953 | 22,993–23,328 | 0.518, 0.485, 0.496, 0.490, 0.492 | 0.186–0.205 ms |
| 16 | 40,000 | 151,877–162,353 | 115,622–170,156 | 0.729, 0.855, 1.043, 1.089, 1.119 | 0.457–1.020 ms |

All five proxy phases in each mode completed 40,000 correct echoes with zero errors. The window-1 proxy added measurable loopback latency in this environment. Window-16 proxy throughput changed substantially across rounds while direct throughput also drifted, so the later rounds do not establish a stable throughput advantage. These runs cannot be compared as an isolated code-level change against earlier Windows measurements.

Raw output: [window 1](2026-09-25-linux-container-post-transfer-window1.txt), [window 16](2026-09-25-linux-container-post-transfer-window16.txt). To repeat either mode from the repository root after `:proxy-core:installDist`, mount the repository read-only at `/work` in the pinned image, then run:

```sh
mkdir -p /tmp/strataproxy-bench
javac -cp '/work/proxy-core/build/install/strataproxy/lib/*' -d /tmp/strataproxy-bench /work/benchmarks/ProxySessionBenchmark.java
java -cp '/tmp/strataproxy-bench:/work/proxy-core/build/install/strataproxy/lib/*' dev.strataproxy.core.session.ProxySessionBenchmark \
  --connections 4 --messages 10000 --warmup 1000 --payload 4096 --repeats 5 --window 1 --mode post-transfer
```

Use `--window 16` for the second run. This is still a synthetic loopback echo test inside one container. It does not exercise a real Forge client, target modpack, gameplay packets, separate host network or production capacity.

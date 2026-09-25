# Linux container post-transfer session benchmark

Run date: 2026-09-25 (Asia/Shanghai). Repository revision at measurement: `3f31bc1d0f10f2f91e9979154bcd352de2a9fe60`; latest core change: `562e00be8e176784e458f0b99b5815a15a1b1212`. Before measuring, `./gradlew.bat :proxy-core:installDist --rerun-tasks --no-daemon` rebuilt all distribution tasks from the checked-out sources. Host: Windows 11, AMD Ryzen 7 7800X3D. Container: Docker Desktop Linux, 16 exposed CPUs, 20.97 GB reported memory, `eclipse-temurin:25-jdk` image digest `sha256:97014c4b396021f9ddb7d592a7dbedb0c4e4215c29e03dc01c393558aefb71c2`, OpenJDK 25.0.4+7.

Each mode ran in a fresh container and JVM. Direct and proxy phases within a mode used the same JVM, fake TCP backend, client code, four concurrent clients, 4,096-byte synthetic PLAY body and in-flight window. Proxy clients transferred from `bench` to `replacement` before warmup; direct clients connected to the same `replacement` backend. Login, transfer and 1,000 warmup exchanges per client were excluded from timing. Direct/proxy phase order alternated across five rounds. Every timed echo was checked for packet ID, sequence, length and body.

| Window | Timed echoes per phase and round | Direct roundtrips/s range | Proxy roundtrips/s range | Paired proxy/direct ratios, rounds 1–5 | Proxy p95 range |
| ---: | ---: | ---: | ---: | --- | ---: |
| 1 | 40,000 | 44,427–47,635 | 23,138–23,768 | 0.521, 0.493, 0.493, 0.501, 0.497 | 0.182–0.195 ms |
| 16 | 40,000 | 156,284–165,556 | 122,656–169,366 | 0.771, 0.831, 1.014, 1.084, 1.011 | 0.454–0.944 ms |

All five proxy phases in each mode completed 40,000 correct echoes with zero errors. The window-1 proxy added measurable loopback latency in this environment. Window-16 proxy throughput changed substantially across rounds, so the later rounds do not establish a stable throughput advantage. These runs cannot be compared as an isolated code-level change against earlier Windows measurements. An initial run using an older local distribution was discarded when its artifact timestamp revealed that the binary had not been explicitly rebuilt from the current checkout; the linked raw outputs are from the forced rebuild only.

Raw output: [window 1](2026-09-25-linux-container-post-transfer-window1.txt), [window 16](2026-09-25-linux-container-post-transfer-window16.txt). To repeat either mode from the repository root after `:proxy-core:installDist`, mount the repository read-only at `/work` in the pinned image, then run:

```sh
mkdir -p /tmp/strataproxy-bench
javac -cp '/work/proxy-core/build/install/strataproxy/lib/*' -d /tmp/strataproxy-bench /work/benchmarks/ProxySessionBenchmark.java
java -cp '/tmp/strataproxy-bench:/work/proxy-core/build/install/strataproxy/lib/*' dev.strataproxy.core.session.ProxySessionBenchmark \
  --connections 4 --messages 10000 --warmup 1000 --payload 4096 --repeats 5 --window 1 --mode post-transfer
```

Use `--window 16` for the second run. This is still a synthetic loopback echo test inside one container. It does not exercise a real Forge client, target modpack, gameplay packets, separate host network or production capacity.

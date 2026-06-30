# Zstd Compression Tuning

This guide explains how to evaluate StrataProxy's experimental Zstd codec path for modded Minecraft traffic. Keep `compression.codec: zlib` for vanilla-compatible production traffic unless your client, proxy, and backend path all negotiate the same Zstd frame format.

The current repository ships the Zstd frame codec, optional dictionary support, configuration validation, and a separate 1.7.10 client prototype. A live deployment still needs a matching negotiation and relay path before `codec: zstd` is safe for normal users.

## Why Minecraft Needs Packet-Aware Compression

Minecraft traffic is not one uniform stream:

- Movement, keepalive, interaction, and small entity updates are latency-sensitive and usually too small to compress profitably.
- Login, registry sync, NBT-heavy payloads, chunk bursts, and many mod custom payloads are large or repetitive enough to benefit.
- Some mod payloads may already be compressed or encrypted at the application layer; recompressing them can waste CPU.

The practical goal is not "compress everything". The goal is to spend CPU only where saved bytes reduce player latency, login spikes, or bandwidth cost.

## Configuration

Edit `proxy-app/src/main/resources/config/strataproxy.yml` for local development, or the external YAML file you pass to the packaged application in production.

```yaml
compression:
  mode: adaptive
  codec: zlib
  minThreshold: 256
  maxThreshold: 8192
  cpuGuard: 0.75
  rewriteEnabled: false
  rewriteMaxEventLoopDelayMillis: 25
  zstdLevel: 1
  zstdDictionaryPath: ""
```

Use `codec: zlib` for normal operation. Use `codec: zstd` only for a controlled modded endpoint where the client and proxy use the same codec, threshold, level, and dictionary bytes.

After training a dictionary, point `zstdDictionaryPath` at the generated `.zdict` file:

```yaml
compression:
  mode: adaptive
  codec: zstd
  minThreshold: 512
  maxThreshold: 8192
  cpuGuard: 0.75
  rewriteEnabled: false
  rewriteMaxEventLoopDelayMillis: 25
  zstdLevel: 1
  zstdDictionaryPath: "data/zstd/registry.zdict"
```

For the packaged app, start StrataProxy with the same config file you edited:

```powershell
.\proxy-app\build\install\strataproxy\bin\strataproxy.bat --config .\config\strataproxy.yml
```

Build the admin CLI before collecting or training samples:

```powershell
.\gradlew.bat --no-daemon :proxy-admin-cli:installDist
```

`zstdLevel` accepts `-5` through `22`:

| Level | Use Case | Trade-off |
| --- | --- | --- |
| `-5` to `-1` | CPU-constrained servers, high player count, latency-first paths | Fastest, lower ratio |
| `1` | Default starting point for Minecraft | Strong speed/ratio balance |
| `2` to `3` | Bandwidth-constrained links with spare CPU | Better ratio, more CPU |
| `4` to `9` | Batch-like login/registry/chunk bursts only | Measure carefully; can add latency |
| `10+` | Offline analysis or non-realtime transfers | Usually too expensive for gameplay |

## Dictionary Training Workflow

Zstd dictionaries help most when payloads are small and similar. For Minecraft, train separate dictionaries for separate payload families instead of one universal dictionary.

Good sample families:

- NBT data from similar tile entities, inventories, or mod state.
- Registry and configuration sync payloads from the same modpack.
- Chunk section or palette payloads from the same world/modpack.
- Large Forge/Fabric custom payloads with repeated structure.

Poor samples:

- Movement or keepalive packets.
- Already compressed blobs.
- Random IDs, signatures, or high-entropy binary data.
- Mixed unrelated payload families.

Recommended process:

1. Capture uncompressed packet payloads by packet family. Do not train on encrypted wire bytes.
2. Split samples into training and validation sets. A simple 80/20 split is enough.
3. Start with a `16 KiB` dictionary. Try `8 KiB`, `32 KiB`, and `64 KiB` only if validation improves.
4. Use the same dictionary bytes on every client, proxy, and backend participant.
5. Version dictionaries by hash. Reject or bypass Zstd if the client dictionary hash does not match.

Built-in collection and training flow:

```powershell
# 1. Start a bounded capture on one backend and one direction.
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat `
  --base-url http://127.0.0.1:8080 `
  zstd-samples start `
  --id registry-train `
  --server survival-1 `
  --direction backend_to_frontend `
  --max-samples 5000 `
  --max-bytes 32768 `
  --duration-ms 300000

# 2. Let representative players log in, switch dimensions, open modded UIs,
#    and load chunks. Then export the retained prefix bytes as .bin samples.
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat `
  --base-url http://127.0.0.1:8080 `
  zstd-samples export registry-train `
  --out .\samples\registry

# 3. Train a dictionary from exported .bin samples.
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat `
  zstd-samples train `
  --in .\samples\registry `
  --out .\data\zstd\registry.zdict `
  --max-dict 16384 `
  --level 1

# 4. Stop the capture when finished.
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat `
  --base-url http://127.0.0.1:8080 `
  zstd-samples stop registry-train
```

Java helper example:

```java
var dictionary = MinecraftZstdDictionaryTrainer.train(samples, 16 * 1024, 1);
Files.write(Path.of("data/zstd/nbt.zdict"), dictionary);
```

`zstd-samples export` writes `sample-000001.bin` style files plus `manifest.json`. The samples are captured prefix bytes, so choose `--max-bytes` large enough to retain the full payload family you want. For dictionary training, prefer captures from one packet family or one modded workflow at a time.

Command hierarchy:

| Command | Purpose |
| --- | --- |
| `zstd-samples list` | List active sample capture sessions |
| `zstd-samples start` | Start a bounded capture with Zstd-friendly defaults |
| `zstd-samples get <id>` | Print one capture and its retained samples |
| `zstd-samples stop <id>` | Stop and remove a capture |
| `zstd-samples export <id> --out <dir>` | Decode `prefixBase64` samples into `.bin` files and `manifest.json` |
| `zstd-samples train --in <dir> --out <file.zdict>` | Train a `.zdict` from exported `.bin` files |

## Threshold Selection

Start conservative:

| Payload | Suggested Action |
| --- | --- |
| `< 256 B` | Never compress |
| `256 B - 1 KiB` | Compress only if dictionary validation proves a win |
| `1 KiB - 8 KiB` | Good dictionary target for NBT, registry, and custom payload families |
| `8 KiB - 1 MiB` | Plain Zstd level `1` is usually enough |
| `> 1 MiB` | Force only when historical ratio is good and event loop delay is healthy |

For live gameplay, prefer raising the threshold over raising the compression level. Higher levels increase encoder CPU; a bad threshold increases both CPU and latency.

## Expected Improvement

Use these numbers as planning assumptions, not guarantees:

- Upstream Zstd's Silesia benchmark reports `zstd -1` at ratio `2.896`, compression `510 MB/s`, and decompression `1550 MB/s`, compared with `zlib -1` at ratio `2.743`, compression `105 MB/s`, and decompression `390 MB/s`.
- Zstd upstream documents dictionary mode for small data and states that type-specific dictionaries can dramatically improve small-payload ratios when samples are correlated.
- On Minecraft traffic, the biggest gains should appear during login, registry sync, dimension change, chunk bursts, and mod configuration payloads.
- Steady-state movement traffic should show little or no bandwidth improvement because it should mostly bypass compression.
- A realistic first target for a modded server is `10% - 30%` lower compressed bytes during login/chunk-heavy bursts. Heavily structured custom payloads can do better; already compressed or random payloads can do worse.

Do not ship based on generic benchmarks. Ship only after measuring your pack's traffic.

## How To Measure

Before changing anything, record a baseline:

```powershell
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat --base-url http://127.0.0.1:8080 compression
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat --base-url http://127.0.0.1:8080 packet-traffic
```

For generated load:

```powershell
.\proxy-query\build\install\strataproxy-query\bin\strataproxy-query.bat --host 127.0.0.1 --port 25577 compression-rewrite-load --connections 100 --virtual-host play.example.net --packets-per-connection 200 --payload-bytes 1024 --threshold 256 --split-frames --parallelism 100 --min-handshaken 100 --min-negotiated 100 --min-packets-sent 20000 --max-failed 0
```

Evaluate:

- `compressedBytes / rawBytes`
- saved bytes per packet family
- compression CPU time
- event loop delay
- p95/p99 login and echo latency
- disconnects or malformed-frame counters

Accept the change only when saved bytes improve without raising event loop delay or p99 latency beyond your server's budget.

## Rollout Checklist

1. Keep `codec: zlib` on public traffic.
2. Enable Zstd only on a separate modded test endpoint.
3. Install the matching client Mod and dictionary.
4. Verify dictionary hash and compression settings during handshake.
5. Canary with staff or a small player group.
6. Compare metrics against the zlib baseline.
7. Roll back immediately if event loop delay, login failures, or p99 latency rises.

## Troubleshooting

| Symptom | Likely Cause | Action |
| --- | --- | --- |
| Client disconnects immediately | Client and proxy frame format mismatch | Disable Zstd and verify negotiation |
| Decompression error | Dictionary bytes differ | Compare dictionary SHA-256 on every participant |
| CPU rises but bytes do not fall | Threshold too low or payload already compressed | Raise threshold and exclude that packet family |
| Login improves but gameplay does not | Expected result | Keep compression focused on bursty payloads |
| Memory pressure rises | Too many dictionaries or large buffers | Use fewer per-family dictionaries and cap frame sizes |

## References

- [Zstandard README and benchmarks](https://github.com/facebook/zstd)
- [Zstandard homepage](https://facebook.github.io/zstd/)

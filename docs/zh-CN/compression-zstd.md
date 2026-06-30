# Zstd 压缩调参指南

本文说明如何评估 StrataProxy 的实验性 Zstd codec 路径，用于 Modded Minecraft 流量。普通 vanilla 兼容生产流量继续使用 `compression.codec: zlib`。只有当客户端、代理和后端路径都协商同一种 Zstd 帧格式时，才可以启用 `codec: zstd`。

当前仓库已经提供 Zstd frame codec、可选 dictionary、配置校验，以及独立的 1.7.10 客户端原型。真正上线前仍需要匹配的协商与 relay 路径；没有这层时，不要直接把 `codec: zstd` 打给普通用户。

## 为什么 Minecraft 需要按包类型压缩

Minecraft 流量不是同一种数据：

- 移动、keepalive、交互、小型实体更新对延迟敏感，而且通常太小，不值得压缩。
- 登录、registry 同步、NBT、大型 chunk burst、很多 Mod custom payload 足够大或足够重复，值得压缩。
- 一些 Mod payload 自己已经压缩或接近随机，再压一次只会浪费 CPU。

目标不是“所有包都压缩”，而是只在节省的字节能降低登录尖峰、带宽成本或玩家可感知延迟时花 CPU。

## 配置

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

正常运行使用 `codec: zlib`。只有在受控 Mod 端点上，且客户端和代理使用同样的 codec、threshold、level 和 dictionary 字节时，才使用 `codec: zstd`。

`zstdLevel` 支持 `-5` 到 `22`：

| Level | 使用场景 | 取舍 |
| --- | --- | --- |
| `-5` 到 `-1` | CPU 紧张、高在线、延迟优先 | 最快，压缩率较低 |
| `1` | Minecraft 默认起点 | 速度和压缩率平衡好 |
| `2` 到 `3` | 带宽紧张且 CPU 有余量 | 压缩率更好，CPU 更高 |
| `4` 到 `9` | 登录、registry、chunk burst 这类接近批处理的路径 | 必须实测，可能增加延迟 |
| `10+` | 离线分析或非实时传输 | 通常不适合 gameplay |

## Dictionary 训练流程

Zstd dictionary 最适合“小而相似”的 payload。对 Minecraft 来说，应该按 payload family 训练多个字典，而不是做一个万能字典。

适合的样本：

- 相似 tile entity、inventory、Mod 状态里的 NBT。
- 同一个 Modpack 的 registry 和 configuration sync payload。
- 同一个世界/Modpack 的 chunk section 或 palette payload。
- 结构重复的大型 Forge/Fabric custom payload。

不适合的样本：

- 移动包或 keepalive。
- 已经压缩过的 blob。
- 随机 ID、签名、高熵二进制数据。
- 混杂无关 payload family 的样本集。

推荐流程：

1. 按 packet family 采集未压缩 payload。不要用加密后的 wire bytes 训练。
2. 拆分训练集和验证集，简单的 80/20 即可。
3. 从 `16 KiB` 字典开始。只有验证集继续变好时，再尝试 `8 KiB`、`32 KiB`、`64 KiB`。
4. 客户端、代理、后端必须使用完全相同的 dictionary 字节。
5. 用 hash 管理 dictionary 版本。握手时 hash 不一致就拒绝 Zstd 或回退。

CLI 示例：

```powershell
zstd --train .\samples\nbt\* -o .\data\zstd\nbt.zdict --maxdict=16384
zstd -D .\data\zstd\nbt.zdict .\validation\nbt\sample.bin -o sample.bin.zst
```

Java helper 示例：

```java
var dictionary = MinecraftZstdDictionaryTrainer.train(samples, 16 * 1024, 1);
Files.write(Path.of("data/zstd/nbt.zdict"), dictionary);
```

## 阈值怎么调

先保守：

| Payload | 建议 |
| --- | --- |
| `< 256 B` | 不压缩 |
| `256 B - 1 KiB` | 只有 dictionary 验证明显收益时才压缩 |
| `1 KiB - 8 KiB` | NBT、registry、custom payload 的好目标 |
| `8 KiB - 1 MiB` | 通常 plain Zstd level `1` 足够 |
| `> 1 MiB` | 只有历史压缩率好且 event loop delay 健康时才 force |

实时 gameplay 里，优先调高 threshold，而不是调高 compression level。level 过高会增加编码 CPU；threshold 太低会同时伤 CPU 和延迟。

## 大概能提升多少

这些数字只能用来估算，不能替代本服实测：

- Zstd 上游 Silesia benchmark 中，`zstd -1` 的 ratio 是 `2.896`，压缩 `510 MB/s`，解压 `1550 MB/s`；`zlib -1` 的 ratio 是 `2.743`，压缩 `105 MB/s`，解压 `390 MB/s`。
- Zstd 上游文档说明 dictionary mode 面向小数据；当样本相关性强时，专用 dictionary 可以显著改善小 payload 压缩率。
- Minecraft 里最可能有收益的是登录、registry sync、切维度、chunk burst、Mod configuration payload。
- 稳态移动流量通常收益接近 0，因为它本来就应该 bypass。
- 对 Modded 服务器，第一阶段可以把目标定为：登录或 chunk-heavy burst 的 compressed bytes 降低 `10% - 30%`。结构很强的 custom payload 可能更高；已经压缩或随机的数据可能没有收益甚至变差。

不要用通用 benchmark 直接上线。上线前必须用你自己的 Modpack 和世界数据测。

## 怎么测

改动前先记录 baseline：

```powershell
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat --base-url http://127.0.0.1:8080 compression
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat --base-url http://127.0.0.1:8080 packet-traffic
```

生成流量压测：

```powershell
.\proxy-query\build\install\strataproxy-query\bin\strataproxy-query.bat --host 127.0.0.1 --port 25577 compression-rewrite-load --connections 100 --virtual-host play.example.net --packets-per-connection 200 --payload-bytes 1024 --threshold 256 --split-frames --parallelism 100 --min-handshaken 100 --min-negotiated 100 --min-packets-sent 20000 --max-failed 0
```

重点看：

- `compressedBytes / rawBytes`
- 每类 packet family 节省的字节
- compression CPU time
- event loop delay
- 登录和 echo latency 的 p95/p99
- 断连、malformed frame、dictionary mismatch 计数

只有在节省字节明显改善，且 event loop delay 与 p99 latency 没超过预算时，才接受改动。

## 上线 checklist

1. 公共流量保持 `codec: zlib`。
2. 只在单独的 Modded 测试入口启用 Zstd。
3. 安装匹配的客户端 Mod 和 dictionary。
4. 握手时校验 dictionary hash 和 compression 参数。
5. 先让管理员或小规模玩家 canary。
6. 和 zlib baseline 对比指标。
7. event loop delay、登录失败或 p99 latency 上升时立即回滚。

## 排障

| 现象 | 常见原因 | 处理 |
| --- | --- | --- |
| 客户端刚连上就断 | 客户端和代理 frame format 不一致 | 关闭 Zstd，检查协商 |
| 解压失败 | dictionary 字节不一致 | 对比每个参与方 dictionary SHA-256 |
| CPU 上升但字节没降 | threshold 太低或 payload 已压缩 | 提高 threshold，排除该 packet family |
| 登录改善，移动阶段没改善 | 正常现象 | 保持压缩聚焦 burst payload |
| 内存压力上升 | 字典过多或 buffer 太大 | 减少 per-family dictionary，限制 frame size |

## 参考

- [Zstandard README and benchmarks](https://github.com/facebook/zstd)
- [Zstandard homepage](https://facebook.github.io/zstd/)

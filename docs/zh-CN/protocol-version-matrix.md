# 协议版本能力矩阵

这张表是 StrataProxy 的 Minecraft 协议兼容契约。以后新增版本时，必须同时更新 `MinecraftProtocolProfile`、测试和这份文档。

状态含义：

- `已支持`：已经进入 `MinecraftProtocolProfile`，并有针对性测试覆盖。
- `部分支持`：有部分行为，但不能宣称完整多版本生产兼容。
- `未支持`：不能对外宣称稳定兼容；必须先补 profile 和测试。

## 当前运行矩阵

| Minecraft Java 版本 | Protocol | 状态 | 登录压缩 | 上行命令包 | Custom Payload 格式 | 后端切服 | Legacy Forge/FML |
| --- | ---: | --- | --- | --- | --- | --- | --- |
| 1.7.10 | 5 | 已支持当前 1.7.10/legacy 切服目标 | 不启用 vanilla login compression | `0x01` chat message | 上行 unsigned-short 长度，下行 varshort 长度 | Respawn 切服；Forge 安全路径为 Join Game + Respawn | 部分支持：REGISTER 跟踪、reset 注入、握手观测/gate、非 FML payload 排队、plugin-channel replay |
| 1.8.x | 47 | 已支持当前压缩和 legacy 切服目标 | 支持 Login Set Compression `0x03` | `0x01` chat message | 上下行都是 remaining bytes | 支持压缩/未压缩 Respawn 切服；Forge 安全路径为 Join Game + Respawn | 部分支持：压缩握手观测/gate、压缩 race suppression、压缩 REGISTER replay |
| 1.20.1 | 763 | 部分支持 | 支持 | `0x03`、`0x04`、`0x05` | 只在已有 sampler 覆盖范围内支持现代上行 payload | 不支持 legacy backend replacement | 不支持 legacy Forge/FML 状态机 |
| 其他 1.9+ 版本 | 各不相同 | 未支持 | 不能只按版本号推断 | 未定义 | 未定义 | 未支持 | 未支持 |
| 当前/latest Java 版本 | 各不相同 | 未支持，直到补 profile | 必须从目标版本协议数据读取 | 必须建模 | 必须建模 | 必须建模 | 现代模组加载器需要单独状态机 |

重要压缩规则：protocol 5 不启用 vanilla 登录压缩。旧代理里存在 1.7.x play-state 压缩包的历史实现，但 StrataProxy 不能把它当作普通 login compression。

## 新版本必须补齐的 Profile 字段

| 字段 | 原因 |
| --- | --- |
| protocol number | 路由过滤和 status response 需要 |
| login packet id | Login Success、Disconnect、Encryption Request、Set Compression 都依赖它 |
| compression framing | 登录协商后 packet 是否压缩以及如何解码 |
| serverbound command packet id | `/server` 等代理命令必须被代理吃掉，不能发给后端 |
| custom payload packet id | Forge/Fabric/Bungee/Velocity plugin message 都依赖它 |
| custom payload 长度格式 | 1.7.10 和 1.8 不同，现代版本又是另一套 payload 语义 |
| Join Game / Respawn id 和布局 | 切服时要安全转换新后端首个 Join Game |
| player list / scoreboard / team 清理包 | 安全切服需要清客户端状态 |
| Forge/mod-loader 握手阶段 | 大型模组服切服需要 reset、registry 和 payload 排队 |
| forwarding/login plugin 行为 | Velocity modern、Bungee legacy 身份转发都和版本有关 |

## 每个版本的测试门槛

| 门槛 | 1.7.10 | 1.8.x | 1.20.1 | 新版本 |
| --- | --- | --- | --- | --- |
| 命令拦截 | 已覆盖 | 通过 legacy profile 路径覆盖 | 已覆盖 | 必须覆盖 |
| 真实 TCP 后端切服 | 已覆盖 protocol 5 `/server` | 已有压缩切服单元覆盖 | 未覆盖 | 宣称切服支持前必须覆盖 |
| 登录压缩 | 明确禁用 | 已覆盖 | 已覆盖现代路径 | 必须覆盖 |
| custom payload 解析 | 已覆盖 | 已覆盖压缩/未压缩 | 部分覆盖 | 必须覆盖 |
| Forge 握手跟踪 | 已覆盖 | 已覆盖压缩/未压缩 | 不适用 | 按 Forge/Fabric 设计补 |
| Forge race suppression | 已覆盖 | 已覆盖压缩/未压缩 | 不适用 | legacy Forge 适用时必须覆盖 |
| plugin channel REGISTER replay | 已覆盖 | 已覆盖压缩/未压缩 | 不适用 | 需要 replay 的版本必须覆盖 |
| 真实大型模组服验收 | 尚未证明 | 尚未证明 | 不适用 | 生产宣称前必须验收 |

## 实现规则

新增版本时，不要直接在 relay handler 里散落 packet id。先扩展 `MinecraftProtocolProfile`，再让 relay/controller 通过 profile 能力工作。如果一个行为无法用 profile 表达，说明应该提取专门状态机，而不是继续在 relay 里加版本判断。

## 后续路线

1. 抽出明确的 `ConnectionStateMachine`：登录、压缩、play、mod-loader 阶段。
2. 抽出 `TransferOrchestrator`：切服生命周期、初始 clientbound 包、回滚和指标。
3. `MinecraftProtocolProfile` 只保留数据：packet id、格式、布局和能力开关。
4. 按版本组补 profile 和测试，再宣称支持：
   - 1.9-1.12.2：legacy Forge 时代。
   - 1.13-1.16.5：扁平化与 namespaced payload 过渡。
   - 1.17-1.18.2：现代网络变更。
   - 1.19-1.20.6：签名聊天、登录/configuration 过渡。
   - 1.21+ 和 26.x 当前版本。

## 参考

- Minecraft Wiki 的 protocol version 数据说明，现代构建可从 client/server jar 的 `version.json` 读取 `protocol_version`。
- Mojang 已宣布 Java Edition 从 2026 年开始使用 `26.x` 年份式版本号；因此版本名和 protocol number 必须分开建模。

# 测试、烟测与性能验证

## 开发验证

```powershell
.\gradlew.bat check
.\gradlew.bat :proxy-core:installedDistSmokeTest
.\gradlew.bat :proxy-core:permissionAcceptance
.\smoke\container-network.ps1
sh smoke/container-channel.sh
```

定向测试覆盖控制通道认证与代次、目录租约、插件生命周期与超时、协议边界、relay 背压，以及合成 TCP 登录和切换。初次路由最新专项验收包括自动化覆盖和实际 Java 8 Uranium 后端协议探针，见[2026-09-27 路由验收](../smoke/results/2026-09-27-initial-routing.md)；它不是完整模组包视觉验收。2026-09-26 Prism 记录保留为其提交和环境下的历史真实客户端/配对测量，见[历史 Prism 记录](../benchmarks/results/2026-09-26-prism-pack.md)。下面其他历史结果也只支持其记录注明的范围。

`smoke/container-network.ps1` 使用 Docker bridge 中的独立代理、后端和客户端容器，依次检查静态配置和控制通道注册、登录及 PLAY 转发；控制通道模式还验证注销后已连接会话继续转发。PowerShell 7 和 Docker Desktop 是运行前提。`smoke/container-channel.sh` 在 Unix shell 下验证控制通道的同一跨容器路径。2026-09-25 的[容器网络烟测记录](../smoke/results/2026-09-25-container-network.md)是旧实现的历史结果，不代表当前通道已在那次运行中验证。容器烟测不能代替跨物理主机或目标 Forge 整合包实测。

`.gitea/workflows/ci.yml` 在 `main` 推送或手动触发时先执行 `gradlew check :proxy-core:distZip`，再上传 CI 工件并发布 Maven 模块。发布步骤要求 `MAVEN_USER` 与 `MAVEN_PASSWORD` secrets；本地 `check` 不能证明远端 Maven 发布成功。版本规则、七个坐标和发行 ZIP 见[发布与依赖版本](publishing.md)。

`check` 包含 `permissionAcceptance`：使用发行包中的实际 LuckPerms JAR、隔离插件类加载器和临时 H2 数据库，验证原生命令、授权/拒绝/未设置、组继承、临时节点到期、后端上下文、连接代次隔离及关闭后重新打开数据库。输出位于 `proxy-core/build/permission-acceptance/run-*/`，成功标记为 `PERMISSION_ACCEPTANCE_PASS`。它使用 API 玩家探针，不连接真实 Minecraft 客户端，也不证明外部 SQL/Redis 联调成功；真实会话的权限准备和释放由 TCP 生命周期测试另行覆盖。

如果本地已有 Uranium 1.7.10 可运行包及其编译好的 `MinecraftProtocolProbe`，可运行 `smoke/local-uranium.ps1 -BundlePath <包目录> -ProbeClassesPath <探针类目录>`。脚本复制服务端到忽略目录，启动 Java 8 后端与当前安装包，再让探针经代理完成状态查询、FML 登录、Join Game 和持续 Keep Alive，结束时停止两个进程。本地一次结果与具体前提见 [最小 Uranium 联机记录](../smoke/results/2026-09-25-local-uranium.md)。初次路由的当前 `ProxyMain` 协议探针结果另见[路由验收](../smoke/results/2026-09-27-initial-routing.md)。真实客户端记录见下段和其单独的历史验收档案；它们不应与当前路由分支的完整模组包验收混为一谈。

`smoke/local-uranium-transfer.ps1 -BundlePath <包目录>` 复制并启动两台 Uranium，然后编译仓库中的协议探针，通过实际 `ProxySessionListener` 请求从旧服切换到新服。加上 `-InstalledPlugin` 则启动已安装的 `ProxyMain`：旧服由静态配置注册，临时插件用 `Servers.register` 注册目标服，在初始落点回调读取 `ServerView`，再通过 `Players.transfer` 发起转服。默认模式使用协议探针核对 FML 重置、重新握手、世界切换包和目标连接的 Keep Alive，见 [两台 Uranium 转服记录](../smoke/results/2026-09-25-local-uranium-transfer.md)。`-InstalledPlugin -ReturnToOld` 让协议探针验证旧服→新服→旧服的两次切换。`-InstalledPlugin -PrismClient` 改用本机 Prism 中的 `1.7.10` Forge 实例，要求客户端在目标服保持连接 10 秒；可同时使用 `-ReturnToOld` 验证真实客户端往返。两个真实客户端脚本都可用 `-PrismInstance <实例名>` 选择其他实例。若实例的文件夹名与启动名不同，另传 `-PrismInstanceFolder <文件夹名>`，以便准确定位并清理这次启动的客户端；`-PrismPath` 指向其他 Prism 安装位置。`smoke/local-uranium.ps1 -PrismClient` 可单独验证首次登录。这些实测仍不能替代目标整合包验收。

安装包转服烟测可加 `-DebugSession`，在本次复制的 Uranium 配置中开启登录阶段日志，并将代理会话的连接、登录写入和失败路径 DEBUG 日志写入所打印运行目录的 `proxy.stdout.log`。加 `-TraceBackend` 会在代理与两台 Uranium 之间放置本地 TCP 中继，并将每个方向的前 512 字节记录到运行目录的 `tap-old.log` 和 `tap-new.log`；这一诊断选项会改变连接时序。初次登录 EOF 的复现实验见 [运行记录](../smoke/results/2026-09-26-initial-login-repeat.md)；增强日志和中继后捕获的 Uranium 登录停滞及源码假设见 [登录停滞记录](../smoke/results/2026-09-26-uranium-login-stalls.md)。

## 玩家操作与服务器列表验证记录（2026-09-26）

完成[玩家操作设计](player-operations.md)的首批实现后，运行 `./gradlew.bat check --offline` 通过。最终报告共 194 项测试（API 7、核心 174、DNS 6、Agent 7），失败、错误和跳过均为 0；安装包帮助、版本和配置校验通过。

本轮新增或扩展的验证包括：完整连接身份校验；PLAY 消息与普通包连续转发；未就绪、连接不可写及 64 条未完成消息上限；消息排空后主动断开；重复断开与调用方取消；准入/选服等待期间的 LOGIN 断开；Login Success 写入未完成时的 PLAY 包顺序；断开写入失败或停滞时的有界清理；转服暂停期间断开的候选后端与索引释放。在线协议测试用实际 RSA/AES 链路核对加密消息与断开，身份验证器仍为注入实现。

服务器列表测试核对默认/自定义 MOTD、展示人数与实时在线人数、非 BMP 字符、JSON 转义、完整响应长度、PNG 文件/尺寸/路径校验，以及经真实 TCP 管线的 STATUS 与 Ping/Pong。插件宿主关闭时先终止事件派发再撤销订阅，避免排队准入链跳过已撤销策略而返回允许。文档相对链接检查通过。

本轮没有使用真实 Mojang 登录会话、目标整合包或生产负载；没有进行性能对比。远端 CI 的执行结果单独记录在 PR 中，本地通过不表示 Runner 或部署环境已验收。

## 统一事件模型验证记录（2026-09-26）

统一为 `Event<R>` / `EventListener<E,R>` 后运行 `./gradlew.bat check --offline`。最终报告共 178 项测试（API 7、核心 158、DNS 6、Agent 7），失败、错误和跳过均为 0；安装包帮助、版本和配置校验通过。

本轮覆盖统一订阅的类型推断、准入的允许/拒绝/空结果/异常/超时/过载、异步通知按监听器和事件顺序完成、通知超时后的队列推进、空通知 stage 的异常隔离，以及取消后停止监听器链、取消回调离开调用线程、宿主关闭后迟到 stage 的取消。既有合成 TCP 登录、身份验证顺序、转服提交/断线通知、命令与普通 PLAY 转发回归通过。文档相对链接检查通过。

本轮没有运行目标整合包、真实 Mojang 在线服务或事件吞吐基准。测试中的在线身份验证器为注入实现；这些结果验证功能与并发边界，不能推出性能收益、零开销或生产容量。
## 历史类型化事件验证记录（2026-09-26）

以下结果针对当时的事件 API 与派发实现。运行 `./gradlew.bat check --offline` 的报告共 174 项测试（API 7、核心 154、DNS 6、Agent 7），失败、错误和跳过均为 0；安装包帮助、版本和配置校验通过。这些数字只对应当时的实现；当前统一 `Event<R>` / `EventListener<E,R>` 模型的验证见上一节。

当时的覆盖包括认证前/认证后拒绝、加密断开消息、异步等待与取消、超时和容量上限，启动阶段注册与冻结、异步准入期间注销订阅、通知顺序/异常隔离/有界队列、启动失败时撤销订阅。合成 TCP 流程还核对首次进入后端、转服提交与断线通知，以及失败/同服转服和重复关闭不会多发通知。普通 PLAY 聊天与代理命令回归通过。

这轮旧模型验证没有运行吞吐对比或目标整合包测试；在线认证测试使用注入的会话验证器。它不能推出统一事件系统无开销或已达到生产容量。

## 历史访问检查验证记录（2026-09-26）

以下记录验证的是 2026-09-26 当时基于专用访问钩子的实现。它是历史结果；类型化事件 API 的验证见上一节。

新增访问检查时先运行 `./gradlew.bat check --offline --rerun-tasks`；最后的并发清理修正后，再运行 `./gradlew.bat check --offline`，重新执行受影响的核心测试与发行包烟测。最终报告共 167 项测试（API 6、核心 148、DNS 6、Agent 7），失败、错误和跳过均为 0；安装包帮助、版本与核心配置校验通过。

新增覆盖包括协议解析前的 IP 拒绝、身份建立后的玩家拒绝、异步等待期间的数据回放、加密登录断开消息、断线取消、超时、异常、工作队列与未决 stage 容量上限，以及进入 PLAY 后不重复调用访问检查。在线身份测试使用注入的会话验证器，未连接真实 Mojang 服务。本次未测量接入吞吐或真实数据库延迟；以下基准均保留各自的历史版本与验证范围。

## 泄漏闸门与变异检查

`proxy-core` 的测试 JVM 以 `-Dio.netty.leakDetection.level=paranoid` 运行，`dev.moonbridge.testing.LeakGate` 自动注册到每个测试类：类结束后强制 GC 并读取 Netty 的泄漏报告，有未释放的缓冲区就使该类失败。`LeakGateTest` 证明它能发现故意制造的泄漏。注意 `Unpooled.wrappedBuffer(byte[])` 创建的缓冲区不被追踪，涉及它们的测试应直接断言 `refCnt()`。

2026-10-05 对手写 `release()` 位置做过一次变异检查（把单个 `release()` 换成不改引用计数的空操作，再跑覆盖它的测试）：对 relay、帧处理器、Tab 补全、协议编码和加解密共 47 处，改造前 23 处无人守护；集中到 `ByteBufs`、`FrameTransformHandler` 并补测试后，剩余 33 处中 32 处被抓到，最后一处随加解密处理器改用 Netty 基类而消失。`ConnectionGate` 与 `Session` 的第二轮检查没有跑完（已完成 11/24：`ConnectionGate:71`、`Session:366/906/911` 当时未被守护，其中 `Session:366/906/911` 已改为复用被测试的辅助代码，`ConnectionGate` 补了单元测试）；`Session` 其余约 10 处（主要是转服开场帧 `transferOpening` 与断开包构造）没有被变异验证。

## 合成 relay 基准

`benchmarks/run-relay.ps1` 比较同一 JVM、同一个本机回声后端上的直连、原始字节 relay、按帧 relay，以及安装了实际 `KeepAliveBridge` 的按帧 relay。每个连接先预热，再重复发送固定长度的合成 Minecraft 帧并读取同样长度的回声；连接数、每连接消息数、预热数、帧负载字节数、重复轮数和同时在途的消息数均可配置。`-Window 1` 是逐包等待回声；更大的窗口使用独立写线程持续发送，读线程按顺序核对回声，并用信号量限制在途消息数。基准在轮次间交替执行三种 relay，并在首尾测直连基线。输出往返吞吐、按单向传输字节计算的 MiB/s、往返延迟 p50/p95/p99、JVM GC 次数/耗时和堆已用量变化。

```powershell
.\benchmarks\run-relay.ps1 -Connections 8 -Messages 2000 -Warmup 200 -Payload 4096
.\benchmarks\run-relay.ps1 -Connections 8 -Messages 2000 -Warmup 200 -Payload 4096 -Window 16
```

该基准只覆盖本机 TCP 回声和三种 relay 数据路径。不同窗口使用不同的客户端发送方式，应在相同窗口内比较。负载是合成帧，不含登录、Forge 握手、模组流量、转服时的实体 ID 改写或真实客户端/后端行为；结果不代表 1.7.10 整合包等价性能，也不设 CI 性能门槛。堆变化是阶段前后的粗略观测，不是分配速率；请在目标机器、JDK 和连接规模上多轮运行并记录环境，避免把单次结果当成容量承诺。

本地一次测量的环境、参数与原始输出见 `benchmarks/results/2026-09-25-local-relay.md`。
加入实际 `KeepAliveBridge` 后的配对测量见 `benchmarks/results/2026-09-25-keepalive-bridge.md`；两份结果使用的 relay 事件循环安排不同，不能直接视为前后性能对比。

## 真实 TCP 慢接收端背压回归

`RawRelaySocketTest` 使用实际 loopback TCP 和 Netty NIO 通道，缩小 socket 发送缓冲与 Netty 写水位，让停止读取的接收方造成真实的待发送积压。测试观察在目标仍有未完成写入时源读取停止（Linux 上短读取可能低于高水位，因此不以不可写状态为条件）、排队字节有界，恢复接收后逐字节校验双向数据，并覆盖拥塞中接收方断开后的通道与待发送写入清理。

```powershell
./gradlew.bat :proxy-core:test --tests '*RawRelaySocketTest'
```

这是有意限制缓冲的原始转发层回归，不经过登录、加密或 Forge 协议，不代表默认配置下的容量或目标整合包表现。现有 `RawRelayTest` 继续覆盖暂停、恢复、交接及引用计数的确定性边界。

## 合成会话基准

`benchmarks/run-proxy-session.ps1` 在同一 JVM 中比较直接连接模拟后端，以及经过实际 `ProxySessionListener` 登录、落点选择和会话转发后连接同一后端。客户端先完成离线登录，读取 Join Game 和 Position and Look，再预热；计时仅包含固定 PLAY 帧的往返。`-Window` 设置每个客户端允许的在途请求数，默认 `1`（stop-and-wait）；大于 `1` 时客户端最多连续发送 Window 个帧，再按 TCP 顺序读取回声并逐条校验，每收到一条便补发一条。预热和计时阶段、直连和代理都使用相同的发送算法。测得的单条延迟从该请求写出前计时到其回声读完；延迟样本和在途时间戳都使用有界数组，受 `Connections`、`Messages` 和 Window 上限约束。出现错误回声或阶段超时时基准以失败退出。

```powershell
.\benchmarks\run-proxy-session.ps1 -Connections 4 -Messages 1000 -Warmup 100 -Payload 1024 -Repeats 2 -Window 1
.\benchmarks\run-proxy-session.ps1 -Connections 4 -Messages 1000 -Warmup 100 -Payload 1024 -Repeats 2 -Window 1 -Mode post-transfer
```

2026-09-25 的多轮、同条件本机测量及原始输出见 [生命周期修复后的会话基准记录](../benchmarks/results/2026-09-25-session-after-lifecycle.md)。更早的测量见 [原会话基准记录](../benchmarks/results/2026-09-25-current-session-benchmark.md)。这些记录对应各自注明的源码版本，不是当前事件 API 的性能结果，也不代表后端负载观测。

`-Mode post-transfer` 让代理客户端先从 `bench` 转到 `replacement`，直连客户端直接进入同一个 `replacement` 模拟后端；转服与预热均在计时外。两后端使用不同玩家实体 ID，因此代理在转服后仍运行实体 ID 映射路径。[转服前后会话基准记录](../benchmarks/results/2026-09-25-post-transfer-session.md) 包含窗口 1 和 16 的同条件多轮测量及原始输出。结果没有显示值得据此修改普通转发路径的稳定差异。

加入 Forge 握手阶段旧世界数据包过滤后，在该历史版本上重跑的 [转服后基准](../benchmarks/results/2026-09-25-forge-transfer-gate-followup.md) 与先前结果的范围重叠；合成流量没有显示稳定的大幅退化，目标整合包仍需单独测量。

同一提交在独立 Linux 容器中的 [转服后会话基准](../benchmarks/results/2026-09-25-linux-container-post-transfer.md) 也完成了窗口 1 和 16 的各五轮对照与逐包校验。该结果仅验证另一运行环境中的合成路径；目标整合包和跨主机网络仍需实测。

2026-09-25 会话路径的 [JFR 采样记录](../benchmarks/results/2026-09-25-session-jfr-screening.md) 只提供了分配线索，代理 I/O 线程的 CPU 执行样本不足以定位热点；因此没有据此改动普通转发路径。

[玩家命令拦截初筛](../benchmarks/results/2026-09-26-command-interceptor.md) 比较同一合成 PLAY 回声流量下启用与关闭命令拦截器的结果；三轮吞吐和延迟范围重叠。该实验没有发送命令，也不能替代目标整合包的容量验收。

将 `-Window` 改为 `16` 可测每连接最多 16 个在途往返的情形。

`-Burst N`（直接运行 Java 时为 `--burst N`）让每次往返由客户端一次写出 N 个帧、模拟后端一次 flush 回声，接近一个服务器 tick 内的多个小 PLAY 包；此时 Window 按批计数。输出另含代理 I/O 线程每帧 CPU 时间，以及在 Linux 上读取 `/proc/net/snmp` 得到的全机 TCP 发送段数/帧（含 ACK，其他平台显示 `n/a`）。[2026-10-05 批量 flush 记录](../benchmarks/results/2026-10-05-relay-batched-flush.md)用它比较逐帧 flush 与按读取批次 flush。`-Transport auto|nio|epoll|kqueue`（`--transport`）选择代理使用的网络传输层，默认 `auto`；[2026-10-05 传输层记录](../benchmarks/results/2026-10-05-network-transport.md)比较了 epoll 与 NIO。

原 stop-and-wait 小样本见 `benchmarks/results/proxy-session-benchmark-smoke-2026-09-25.md`；Window 参数的小样本和 Window=1/16 重复测量见 `benchmarks/results/2026-09-25-proxy-session-window.md`。这些是环回网络上的合成帧对照，不能代表 Forge 整合包、真实后端或跨主机部署的性能。

## 富文本与命令补全验证（2026-09-27）

实现设计见[消息与命令补全](messages-and-completion.md)。本地 `gradlew check` 通过：217 项测试（API 7、核心 197、DNS 6、Agent 7），失败、错误、跳过均为 0；发行包帮助、版本、配置校验通过。

新增验证包含组件构造与旧版 JSON 字段、模板变量作为文字插入、消息深度/节点/字节限制；根命令与后端候选合并、参数补全、原始后端回复透传；超时、迟到回复隔离、队列上限、转服代次切换、插件注销/关闭与取消回调线程。真实 TCP 测试同时核对富文本命令回复和补全，转服测试核对切换前后的补全上下文。

此外用 Java 8 加载本机 Uranium 的实际组件解析器读取当前发行包生成的 JSON，核对正文、点击命令和悬浮文字通过。未启动真实客户端进行视觉/鼠标交互验收；没有测量本次改动的吞吐收益。最终远端 CI 按对应 PR 检查项记录。

## 后端宿主真实服务端验收

`smoke/backend-uranium.ps1` 将已经接受 EULA 的 Uranium 发行包中的服务端 JAR、运行库和 EULA 复制到全新隔离目录，启动两个 Java 8 后端及实际 Java 25 Proxy。它安装 `MoonBridgeBackend`、两个独立的 Bukkit 业务插件和一个外部 Proxy 验收插件，通过正式插件 API 检查注册与消息服务；不会复制或修改原有世界、模组、插件及凭据。Bukkit 业务插件只使用发行包 `backend-api/` 中的 API JAR 和服务器 Bukkit API 编译，编译路径不包含宿主及传输 SDK，并在实际插件加载器下验证服务类身份一致。

```powershell
.\gradlew.bat check :proxy-core:installDist
.\smoke\backend-uranium.ps1 -BundlePath C:\path\to\accepted-uranium-bundle `
    -BukkitApiJar C:\path\to\accepted-uranium-bundle\Uranium-server.jar
```

脚本默认使用本机 Zulu Java 8 和 Java 25，可通过 `-Java8Home`、`-Java25Home` 指定其他路径。完整成功输出 `BACKEND_URANIUM_PASS`；任一阶段失败会返回非零退出码并保留日志。后台进程有启动、请求、停机期限，退出时只清理本次创建的进程。

验收覆盖 Proxy→后端、后端→Proxy、后端→后端请求，UUID/replyTo、多个独立订阅者接收同一广播、Bukkit 主线程与零在线玩家、业务插件停用隔离、Proxy 重启后的自动重新注册、正常停服快速注销、异常退出后的租约清理和后端重启。具体构件哈希和运行结果见[2026-09-27 后端宿主记录](../smoke/results/2026-09-27-backend-host.md)。

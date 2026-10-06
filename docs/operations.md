# 运维与验证

本文概括配置入口和启动约束。架构边界见[架构说明](architecture.md)；简要项目说明见 [README](../README.md)。实际部署需按自身网络与并发规模验证，验证记录的适用范围见[测试说明](testing.md)。

## 配置和启动

默认配置模板为 [`moonbridge.yml`](../proxy-core/src/main/resources/config/moonbridge.yml)。发行包将示例配置放在 `config/`；自有插件 JAR 可放在 `plugins.directory` 指向的目录。相对 `plugins.directory` 从所选配置文件所在目录解析；`plugins.enabled` 以插件实现类全名为键，空映射不启用外部插件。插件可用设置由各插件在启动时校验。

本地安装发行包并启动：

```powershell
.\gradlew.bat :proxy-core:installDist
.\proxy-core\build\install\moonbridge\bin\moonbridge.bat --validate-config .\proxy-core\build\install\moonbridge\config\moonbridge.yml
.\proxy-core\build\install\moonbridge\bin\moonbridge.bat --config .\proxy-core\build\install\moonbridge\config\moonbridge.yml
```

`--validate-config` 校验核心 YAML、静态后端注册和服务器列表图标；它不会加载插件，所以不会验证已启用插件的设置、后端连通性或登录流程。未知核心配置键会报错。

采用统一事件模型后，已有配置中的 `plugins.accessTimeoutSeconds` 需要改为 `plugins.eventTimeoutSeconds`；旧键已删除，启动时会被当作未知配置拒绝。

## 从 StrataProxy 迁移

MoonBridge 是破坏性改名，旧包名/API、启动程序和配置文件没有兼容别名。升级时将 `strataproxy` 启动程序改为 `moonbridge`、将 `strataproxy.yml` 迁移为 `moonbridge.yml`，并检查 `dev.strataproxy` Java 导入改为 `dev.moonbridge`。旧配置中的 `plugins.initialPlacementTimeoutSeconds` 改为 `initialRouting.timeoutSeconds`；首次登录入口应显式写入 `initialRouting.servers`，不要依赖注册顺序。

后端宿主插件从 `StrataProxyBackend` 改为 `MoonBridgeBackend`。更新业务插件 `plugin.yml` 的 `depend`、安装新的宿主 JAR，并将旧插件配置迁移到新宿主插件的数据目录。消费者需重新编译；已删除的 `backendChannels()` API 应迁移到 `messaging()`，Bukkit 业务插件改用 `backend-bukkit-api` 提供的 `BukkitMessagingService`。迁移后的依赖和宿主接法见[后端 Channel 接入](backend-channel-design.md#后端插件接入)。

## 服务器列表

```yaml
status:
  motd: "MoonBridge"
  maxPlayers: 100
  # icon: "server-icon.png"
```

`motd` 是允许换行的纯文本，最多 1024 个 Unicode 码点。`maxPlayers` 是客户端列表展示人数，范围为 1–1,000,000，不影响实际连接上限 `maxConnections`；在线人数来自代理已发布的玩家会话。省略 `status` 时使用上面的默认文本与人数。

可选 `icon` 必须是 64×64 PNG，文件不超过 64 KiB，使用相对配置文件所在目录的路径。完整 STATUS 响应还须满足协议字符串 32,767 UTF-8 字节上限，文本与图标组合过大时配置校验失败。启动和 `--validate-config` 读取并校验图标，错误时停止；运行中的状态查询复用预构造数据，不读磁盘，也不派发插件事件。更改列表配置需要重启。

## 认证、监听和容量边界

- 必须显式设定 `authentication: OFFLINE` 或 `ONLINE_BUNGEE`。随附示例使用 `OFFLINE` 并绑定字面量 `127.0.0.1`。OFFLINE 信任客户端提交的名字；没有显式设置 `allowOfflinePublicAccess: true` 时只允许字面量回环 IP，避免主机名解析改变监听范围。
- 对外服务使用 `ONLINE_BUNGEE`，并将后端限制为代理可达，禁止客户端绕过代理直连；后端需启用兼容的旧版 Bungee 身份转发。会话验证与加密路径有自动化测试，当前正版账号的真实 Mojang 会话验证、UUID 和皮肤签名转发也已完成端到端实测。
- `maxConnections` 默认 4096，范围为 1–1,000,000；计数包括登录中的客户端。达到上限时新连接直接关闭。按部署资源和连接规模配置，不将其解释为后端容量。
- `transport` 选择玩家连接、后端连接和 DNS 使用的网络传输层，默认 `AUTO`：Linux 上用 epoll，macOS 上用 kqueue，其他平台（如 Windows）或原生库无法加载时（如 ARM/x86 以外的架构、musl 发行版）回退到 NIO，启动日志会输出实际选用的传输层。发行包附带 Linux x86_64/aarch64 的 epoll 与 macOS x86_64/aarch64 的 kqueue 原生库，启动脚本已加 `--enable-native-access=ALL-UNNAMED`。显式写 `NIO`、`EPOLL` 或 `KQUEUE` 会强制使用对应实现；指定的原生传输层不可用时启动失败，不会静默回退。后端控制通道使用独立的阻塞套接字，不受此项影响。
- 发行包默认关闭 Netty 的内存泄漏检测（`-Dio.netty.leakDetection.level=disabled`）：它在每个手写转发/编码路径上都有逐帧开销，合成基准中 burst 场景每帧 CPU 中位数约低 26%（范围有重叠，见[记录](../benchmarks/results/2026-10-05-event-loop-queue-and-netty.md)）。代替它的是测试期的 `LeakGate`：`proxy-core` 的每个测试类都以 PARANOID 级别运行，发现未释放的缓冲区即让该类失败，且有自测证明它能抓到故意的泄漏。引用计数的手写位置已集中到 `ByteBufs`、`FrameTransformHandler` 与 Netty 自带的编解码基类里；仅 `Session` 里两处不可达的防御分支没有测试守护，详见[验证说明](testing.md)。需要在某个部署上重新启用时，设置 `JAVA_OPTS=-Dio.netty.leakDetection.level=simple`（启动脚本里后出现的 `-D` 覆盖默认值）。
- `plugins.eventTimeoutSeconds` 为统一事件链期限，默认 5 秒，可设为 1–30 秒。玩家准入事件按订阅注册顺序共享一个期限；无订阅者时跳过派发。准入队列容量为 128，最多 1024 个未决请求。队列饱和、超时、异常或空结果都会拒绝当前访问：连接准入阶段关闭 TCP 连接，玩家准入阶段返回通用错误文本；插件可通过显式拒绝结果提供登录断开原因。生命周期通知有独立的单线程 FIFO 队列，容量 128，最多一个事件正在派发；单事件链受同一事件期限约束。过载时尽力丢弃并聚合记录，不影响连接处理。
- 未认证帧上限为 4 KiB；后端 Login Success 后切换到协议 5 PLAY 帧上限。握手/身份验证与后端连接/登录各自有 15 秒期限；`initialRouting.timeoutSeconds` 默认 15 秒，可设为 1–120 秒，覆盖插件异步选服和所有候选服务器的 DNS/TCP 建连。`initialRouting.servers` 是无插件策略时显式配置的有序候选服务器名，最多 16 个；默认空列表会拒绝首次登录，不会从动态注册目录猜一个服务器。有初始选服插件时，该插件的选择完全接管路由，失败不会回退到配置列表。只有发送任何 Minecraft 握手/登录字节前的 DNS/TCP 失败可继续尝试候选；开始后端登录后失败会结束本次登录，不重放协议流量。成功登录后，客户端须在两分钟内完成首次 PLAY/Forge 握手。
- 登录拒绝、插件主动断开以及后端关闭/读失败的已提交前端写入最多排空等待 5 秒。转服切换阶段有 15 秒期限；Forge 客户端切换后目标握手最多等待 30 秒。各期限届满可能使当前登录失败或关闭当前会话。

## 可选后端发现

后端控制通道示例见 [`moonbridge-channel.example.yml`](../proxy-core/src/main/resources/config/moonbridge-channel.example.yml)，使用独立监听端口接收实例注册、心跳和双向消息；使用方法与限制见[后端控制通道](backend-channel-design.md)。

Docker 容器间的监听、服务名和地址配置见[配置与部署](backend-channel-design.md#配置与部署)；`sh smoke/container-channel.sh` 可运行跨容器烟测。

控制通道默认关闭。启用后，后端实例通过独立 TCP 连接认证和注册；代理不提供 DNS 发现或 HTTP 注册。代理核心没有内置云实例发现或健康探测。

玩家准入事件 API、检查顺序与 Ban 插件边界见[插件接入说明](plugins.md)。连接准入拒绝发生在 Minecraft 协议开始前，因此没有 Login Disconnect 可供显示；玩家准入发生在身份解析后。`ONLINE_BUNGEE` 仍由核心完成会话验证后才发出玩家事件，离线模式的身份由客户端提供且 `authenticated` 为 false。会话通知不保证 Forge 或玩法世界就绪，也不能用于可靠审计或计费。

## 验证命令和现有证据

项目定义的常规验证入口为：

```powershell
.\gradlew.bat check
```

该任务包含插件 API、消息 API/协议、Java 8 后端 SDK、Bukkit 宿主和核心测试，并检查宿主 JAR 的 Java 8 字节码和依赖边界。核心 `check` 还依赖安装发行包后的帮助、版本及配置校验烟测。详细烟测、性能测量方法与历史记录见[验证方法](testing.md)。

现有测试可用于定位行为范围：

- 登录、认证和协议：`proxy-core/src/test/java/dev/moonbridge/core/auth/` 与 `proxy-core/src/test/java/dev/moonbridge/core/protocol/`
- 会话、断开、转服与缓冲：`proxy-core/src/test/java/dev/moonbridge/core/session/`，重点包括 `SessionTransferTest`、`SessionTransferDispatchTest`、`TransferFrameBufferTest`
- 插件生命周期、访问检查和 API：`proxy-core/src/test/java/dev/moonbridge/core/plugin/PluginHostTest.java`、`proxy-core/src/test/java/dev/moonbridge/core/session/ProxySessionListenerTest.java`、`proxy-core/src/test/java/dev/moonbridge/app/ProxyConfigurationTest.java` 与 `proxy-plugin-api/src/test/`
- 控制通道：`proxy-core/src/test/java/dev/moonbridge/core/control/` 与 `backend-channel-client/src/test/`

仓库另有本地 Uranium 协议探针和 Prism Forge 客户端记录，见 [`smoke/results`](../smoke/results/2026-09-25-local-uranium.md)、[转服记录](../smoke/results/2026-09-25-local-uranium-transfer.md) 和 [真实客户端记录](../smoke/results/2026-09-25-prism-forge-client.md)。这些记录支持其明确写出的单机环境、软件版本和探针路径。已知也出现过真实 Forge 客户端转服候选后端登录停滞，见[调查记录](../smoke/results/2026-09-26-uranium-login-stalls.md)；已有成功样例不构成稳定性或生产验收。

# StrataProxy

StrataProxy 是一个 Java 25 的 Minecraft 后端代理。它只做代理该做的事：接受玩家连接、选择可用后端、维持后端健康状态，并处理认证、转发、压缩及必要的 Forge/Bungee 协议兼容。

项目没有管理后台、HTTP Admin API、管理 CLI、查询协议、数据包分析或监控面板。运行期扩缩容通过代理插件 API 或受限的 Bukkit 后端适配器完成；两者都只能操作后端注册表，不能管理代理进程或读取玩家数据。

## 工程与产物

工程包含三个 Gradle 模块：

- `proxy-core`：可运行的代理、配置、网络转发与插件加载器。
- `proxy-plugin-api`：第三方代理插件唯一需要依赖的公开 API。
- `backend-agent-api`：后端服务端插件使用的 Java 8、平台无关通道 API。

`integrations/bukkit-backend-agent` 是独立的 Java 8 Bukkit 插件；它以 Spigot 1.8 API 编译，只使用 Bukkit 1.7.10 已有的基础 API，因此可用于 1.7.10 Bukkit 派生服务端。

## 构建与启动

需要 JDK 25。执行：

```powershell
.\gradlew.bat --no-daemon check :proxy-core:installDist
```

运行目录位于 `proxy-core\build\install\strataproxy\`。先复制默认配置并校验：

```powershell
Copy-Item proxy-core\src\main\resources\config\strataproxy.yml .\strataproxy.yml
.\proxy-core\build\install\strataproxy\bin\strataproxy.bat --config .\strataproxy.yml --validate-config
.\proxy-core\build\install\strataproxy\bin\strataproxy.bat --config .\strataproxy.yml
```

`network.bind` 是玩家入口，`servers` 至少需要一个静态后端；若启用了 `backendAgent`，可以只通过动态注册提供后端。每次修改 YAML 后重启代理。

## 最小配置

下面是一个本机大厅的最小可用配置。其余字段可保留默认值：

```yaml
network:
  bind: "0.0.0.0:25577"

registry:
  staticServers: true
  healthCheckEnabled: true
  healthCheckMode: "minecraft-status"

servers:
  - name: "lobby-1"
    address: "127.0.0.1:25565"
    tags: ["lobby"]
    weight: 100
    softCapacity: 500
    hardCapacity: 600
    drainMode: false
```

常用配置边界：

- 默认入服选择按后端名称排序，在健康、未排空、协议匹配且未达到硬容量的后端中取第一个。若请求域名匹配后端名称、标签或 `metadata.host` / `metadata.route`，只在匹配的后端中选择；已配置的玩法入口没有可用后端时不会回退到其他玩法。`weight` 和软容量不参与默认选择，可由插件路由策略使用。
- `healthCheckMode: tcp` 只检查 TCP 可连接；`minecraft-status` 还要求后端能正常回复服务器列表状态。
- `forwarding.mode` 可选 `none`、`velocity-modern`、`bungee-legacy`、`bungee-guard`。使用 `velocity-modern` 或 `bungee-guard` 时必须同时设置 `forwarding.secret`，并在后端配置相同密钥。
- `registry.persistenceEnabled: true` 会将标记为持久化的动态后端写入 `registry.persistencePath`；临时后端不会跨重启保留。
- `network.proxyProtocol` 只能用于可信的 HAProxy 或负载均衡器之后，并且上游确实发送 PROXY protocol v1。

## 动态后端：Bukkit 服务端

官方 Bukkit 适配器用于让一个 Bukkit 服务端在启动时注册、定期发送心跳，并在停止时注销。代理端先启用一个仅监听本机的受限端点：

```yaml
backendAgent:
  enabled: true
  bind: "127.0.0.1:25578"
  sharedSecret: "所有受信任子服共用的至少 32 字符随机密钥"
  heartbeatTimeout: "30s"
  maxConnections: 32
  maxQueuedConnections: 64
  maxNonces: 4096
```

构建适配器：

```powershell
.\gradlew.bat -p integrations\bukkit-backend-agent clean jar
```

将 `integrations\bukkit-backend-agent\build\libs\strataproxy-bukkit-backend-agent-*.jar` 放入 Bukkit 的 `plugins/` 目录。首次启动后编辑 `plugins/StrataProxyBackendAgent/config.yml`：

```yaml
proxy:
  host: "127.0.0.1"
  port: 25578
  sharedSecret: "与代理 backendAgent.sharedSecret 完全相同的至少 32 字符随机密钥"

backend:
  name: "survival-1"
  address: "127.0.0.1:25565"
  tags: ["survival"]
  weight: 100
  persistent: false

heartbeatSeconds: 10
unregisterOnDisable: true
```

所有受信任 backend agent 共用 `sharedSecret`；每个 `backend.name` 仍必须唯一。插件每次启动会生成 instance ID，旧实例的心跳或注销不能影响新实例。端点限制请求长度、并发连接、排队和 replay nonce；建议保持 `bind` 在环回地址，或以防火墙限制到可信 Bukkit 主机。

`backend-agent-api` 是平台无关的业务消息契约：Bukkit agent 会将其作为服务发布，未来 Forge agent 实现同一接口即可。业务插件应仅依赖该 API，并在服务端插件描述中声明依赖。通过 `ServicesManager` 获取 `BackendAgentApi` 后，使用 `publish(channel, payload, correlationId, idempotencyKey, mode)` 发布不透明字节载荷，使用 `listen(channel, listener)` 订阅消息；消息由代理生成唯一 `messageId`，可用 `correlationId` 关联业务请求，可靠消息在处理成功后由 agent 自动 ACK。通道是后端 agent 与代理之间的独立长连接，不经过玩家连接，也不依赖 Bukkit 的 Messenger API。API 不引用 Bukkit、Forge 或代理内部实现。

代理插件使用 `PluginContext.channels()` 访问同一 broker，可发布到所有订阅后端或指定后端，并订阅后端发来的消息。`DeliveryMode.RELIABLE` 只保证代理在有界未确认窗口内持续重试到 agent；它不替业务协议定义事务，调用方应使用稳定的幂等键处理超时重试。`BEST_EFFORT` 适合不需要重放的通知。通道名限制为 ASCII 字母、数字、`_`、`.`、`:`、`-`，载荷上限为 48 KiB。

## 代理插件

代理会从配置文件同级的 `plugins/` 目录加载插件 JAR。插件仅依赖 `dev.strataproxy:proxy-plugin-api`，不要依赖 `proxy-core` 的实现类。

本仓库开发时可先发布 API 到本机 Maven：

```powershell
.\gradlew.bat :proxy-plugin-api:publishToMavenLocal
```

后端业务插件的公共契约可同样发布到本机 Maven：

```powershell
.\gradlew.bat :backend-agent-api:publishToMavenLocal
```

插件项目依赖：

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    compileOnly("dev.strataproxy:proxy-plugin-api:0.2.0-SNAPSHOT")
}
```

### 0.2 API 与兼容性验证

`0.2.0-SNAPSHOT` 是破坏性 API 版本：`PlayerView` 与转服事件新增 UUID/连接 ID，转服结果明确为代理网络层 `NETWORK_READY`，不承诺 Bukkit 业务已完成；旧的 `PlayerService.sendPluginMessage` 玩家转发 API 已移除。核心与 proxy/plugin API 使用 Java 25；backend-agent-api 与 Bukkit agent 保持 Java 8 字节码。

发布前必须在目标 1.7.10 Forge 整合包执行连续转服、目标后端不可用、代理/后端重启恢复和至少 24 小时运行测试，并保存版本、mod 列表、日志和结果。单元测试及配置 smoke test 不能替代该门禁。

JAR 根目录需要 `strataproxy-plugin.properties`：

```properties
id=example
name=Example Plugin
version=1.0.0
main=com.example.ExamplePlugin
```

入口实现 `ProxyPlugin`。`PluginContext` 提供命令、事件、分阶段路由、玩家查询/转服、后端查询/注册、调度器和插件日志。动态后端默认是临时的；选择 `ServerPersistence.PERSISTENT` 才会写入持久化注册表。插件只能修改自己创建的后端，不能覆盖 YAML 静态后端或其他插件的后端。

```java
public final class ExamplePlugin implements ProxyPlugin {
    @Override
    public void onLoad(PluginContext context) {
        context.events().subscribe(ProxyStartedEvent.class,
                event -> context.logger().info("Example plugin ready"));
    }
}
```

不要在事件回调或命令处理中阻塞 Netty 线程；耗时操作请交给 `context.scheduler()`。代理内置玩家命令为 `/server <server>`、`/hub`、`/lobby`、`/servers` 和 `/glist`。

### 分阶段路由

插件通过 `context.routes().register(stage, priority, policy)` 注册策略。`INITIAL` 在代理获得玩家身份后、连接第一个后端前执行：离线模式读取 LoginStart 后触发，在线模式完成会话验证后触发。`TRANSFER` 在已连接玩家调用 `context.players().route(playerIdentity, routeKey)` 时触发；它不会因为负载变化自动迁移玩家。直接指定目标的 `transfer(...)` 仍可用，不经过路由策略。

策略返回 `CompletionStage<RouteDecision>`，可以异步查询数据库或远端服务。`pass()` 继续执行下一个策略；`select(serverName)` 选定后端；`reject(reason)` 拒绝本次路由。优先级高的策略先执行，同优先级按注册顺序执行。全部 `pass()` 时，`INITIAL` 使用上面的最小默认入服选择，`TRANSFER` 按 `routeKey` 匹配后端名称、标签或 `metadata.route`。代理会在建立连接前再次检查目标后端的健康、排空、硬容量与协议兼容性。

```java
context.routes().register(RouteStage.INITIAL, 100, route ->
        playerSettings.findSpawn(route.playerIdentity(), route.playerName())
                .thenApply(spawn -> spawn == null
                        ? RouteDecision.pass()
                        : RouteDecision.select(spawn)));
```

路由回调在 Netty 事件循环之外运行；每个策略有 3 秒超时，异常或超时会拒绝本次路由。`RouteContext` 只描述此次路由请求。插件可随时通过 `PluginContext.servers().find(name)`、`firstWithTag(tag)` 或 `servers()` 获取 `ServerView`，自行按玩法、玩家配置或负载选服。`ServerView` 包含健康状态、排空状态、容量、协议范围、标签、元数据和负载；每次查询返回当前快照，不会随状态更新而改变。负载中的玩家数取自代理当前连接，流量是代理侧采样值；它不代表后端 CPU、TPS 或业务队列。插件卸载或加载失败时，代理会注销其路由策略。

## 发布

`release` 会生成代理发行包、插件 API JAR、SBOM、校验和及元数据：

```powershell
.\gradlew.bat --no-daemon release
```

`publish` 会向已配置的 Gitea Maven 仓库发布 `proxy-plugin-api` 和 Bukkit 后端适配器。CI 使用同一套 Gradle 任务；不要把密钥写入配置样例或提交到仓库。

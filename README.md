# StrataProxy

StrataProxy 是一个 Java 25 的 Minecraft 后端代理。它只做代理该做的事：接受玩家连接、选择可用后端、维持后端健康状态，并处理认证、转发、压缩及必要的 Forge/Bungee 协议兼容。

项目没有管理后台、HTTP Admin API、管理 CLI、查询协议、数据包分析或监控面板。运行期扩缩容通过代理插件 API 或受限的 Bukkit 后端适配器完成；两者都只能操作后端注册表，不能管理代理进程或读取玩家数据。

## 工程与产物

工程只有两个 Gradle 模块：

- `proxy-core`：可运行的代理、配置、网络转发与插件加载器。
- `proxy-plugin-api`：第三方代理插件唯一需要依赖的公开 API。

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
  sharedSecret: "替换为至少 32 个字符的随机密钥"
  heartbeatTimeout: "30s"
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

`sharedSecret` 两端必须相同，且不能保留示例中的占位符；配置错误时插件会拒绝启动。端点只接受注册、心跳和注销，心跳超时后代理会移除该后端。建议保持 `bind` 在环回地址，或以防火墙限制到可信 Bukkit 主机。

## 代理插件

代理会从配置文件同级的 `plugins/` 目录加载插件 JAR。插件仅依赖 `dev.strataproxy:proxy-plugin-api`，不要依赖 `proxy-core` 的实现类。

本仓库开发时可先发布 API 到本机 Maven：

```powershell
.\gradlew.bat :proxy-plugin-api:publishToMavenLocal
```

插件项目依赖：

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    compileOnly("dev.strataproxy:proxy-plugin-api:0.1.0-SNAPSHOT")
}
```

JAR 根目录需要 `strataproxy-plugin.properties`：

```properties
id=example
name=Example Plugin
version=1.0.0
main=com.example.ExamplePlugin
```

入口实现 `ProxyPlugin`。`PluginContext` 提供命令、事件、玩家查询/转服、后端查询/注册、调度器和插件日志。动态后端默认是临时的；选择 `ServerPersistence.PERSISTENT` 才会写入持久化注册表。插件只能修改自己创建的后端，不能覆盖 YAML 静态后端或其他插件的后端。

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

## 发布

`release` 会生成代理发行包、插件 API JAR、SBOM、校验和及元数据：

```powershell
.\gradlew.bat --no-daemon release
```

`publish` 会向已配置的 Gitea Maven 仓库发布 `proxy-plugin-api` 和 Bukkit 后端适配器。CI 使用同一套 Gradle 任务；不要把密钥写入配置样例或提交到仓库。

# 插件和游戏内命令

StrataProxy 会从当前配置文件旁边的 `plugins/` 目录加载代理插件。插件 jar 是可选的；目录不存在时代理照常启动。

公开插件接口是 `dev.strataproxy:proxy-plugin-api` artifact。插件项目只应该依赖这个 API；`proxy-core`、`proxy-app` 和其他运行时模块都是内部实现细节。

在本仓库本地开发时，先发布 API 到本机 Maven：

```powershell
.\gradlew.bat :proxy-plugin-api:publishToMavenLocal
```

插件项目使用：

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    compileOnly("dev.strataproxy:proxy-plugin-api:0.1.0-SNAPSHOT")
}
```

## 内置玩家命令

这些命令由代理拦截，不会继续转发到后端服务器：

```text
/server <server>
/hub
/lobby
/servers
/glist
```

`/server`、`/hub` 和 `/lobby` 使用与受支持 BungeeCord `Connect` plugin message 相同的类 Bungee 后端重登录转服流程。

## 插件入口

插件 jar 可以在 jar 根目录放置 `strataproxy-plugin.properties`：

```properties
id=example
name=Example Plugin
version=1.0.0
main=com.example.ExamplePlugin
```

也可以使用标准 Java `ServiceLoader`，提供 `dev.strataproxy.plugin.ProxyPlugin` provider。

插件可以通过 `PluginContext` 注册命令、订阅事件、查询玩家和服务器、注册动态后端、调度异步任务、发起玩家转服。插件代码不要阻塞 Netty event loop。

## 动态后端注册

插件可通过 `context.servers().register(...)` 注册运行期后端，用于服务发现、房间服、副本服或外部编排系统同步。默认注册是 `ServerPersistence.EPHEMERAL`，只影响当前运行中的 proxy；需要重启后保留时，显式使用 `ServerPersistence.PERSISTENT`。

插件注册的 server 会自动写入 owner metadata。默认情况下，插件只能替换、删除或切换 drain 自己注册的 server，不能覆盖 YAML 静态 server 或其他插件注册的 server。

```java
var registration = new ServerRegistration(
        "arena-1",
        new InetSocketAddress("127.0.0.1", 25570),
        Set.of("arena"),
        Set.of("modern-forwarding"),
        ServerProtocolRange.any(),
        100,
        80,
        100,
        false,
        Map.of("group", "arena"),
        ServerPersistence.EPHEMERAL);

context.servers().register(registration).thenAccept(result -> {
    if (!result.success()) {
        context.logger().warn("Failed to register backend: {} {}", result.outcome(), result.message());
    }
});
```

能力名使用和配置文件一致的语义，大小写不敏感，`-` 会按 `_` 规范化，例如 `modern-forwarding` 会映射到 `MODERN_FORWARDING`。

## 最小插件

```java
public final class HelloPlugin implements ProxyPlugin {
    @Override
    public void onLoad(PluginContext context) {
        context.commands().register(new CommandSpec(
                "hello",
                List.of(),
                "",
                "Send a hello message.",
                command -> CompletableFuture.completedFuture(
                        CommandResult.ok("Hello, " + command.source().name() + "."))));
    }
}
```

仓库里提供了独立示例 `examples/hello-plugin`。本地发布 API 后可以构建：

```powershell
.\gradlew.bat :proxy-plugin-api:publishToMavenLocal
.\gradlew.bat -p examples/hello-plugin build
```

把生成的插件 jar 放到当前 StrataProxy 配置文件旁边的 `plugins/` 目录，然后重启代理。

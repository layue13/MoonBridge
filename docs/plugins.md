# 插件开发

插件依赖 `proxy-plugin-api`，不依赖 `proxy-core` 或 Netty。入口实现 `dev.strataproxy.api.Plugin`，提供公开无参构造器，在 JAR 的 `META-INF/services/dev.strataproxy.api.Plugin` 中声明实现类，并由配置中的 `plugins.enabled` 显式启用。

## 生命周期和线程

`onLoad(PluginContext)` 获得 `players()`、`servers()`、`commands()`、SLF4J `logger()` 和不可变 `settings()`。宿主在所有插件加载后收集可选回调，再调用 `onEnable()`；插件全部启用成功后才开放监听端口。初始化失败会终止启动并回收已创建的注册。

插件钩子是明确类型的可选接口，提供方式与初始选服一致。回调由宿主的有界工作池调用，不在玩家 Netty 事件循环上执行。数据库客户端、缓存和外部任务的生命周期由插件管理；返回 `CompletionStage` 表达异步结果，避免在回调里 `join()` 等待另一个任务。各玩家的检查可以并发，插件共享数据必须支持并发访问。

关闭时，宿主先终止未决请求并停止接收新回调，再逆序调用 `onDisable()`。所有关闭钩子共享 10 秒期限；插件忽略中断时仍可能继续运行，因此插件必须自行停止数据库/调度器等资源。随后宿主撤销后端与命令注册、关闭类加载器。当前没有插件热卸载或热加载入口。

## 玩家与后端

- `Players.online()` 和 `find(PlayerIdentity)` 返回不可变 `PlayerView`。`PlayerIdentity` 同时包含玩家 UUID 和本次连接代次；异步转服要保留这个完整身份，不能用旧连接去操作重连后的玩家。
- `Servers.all()` / `find(name)` 返回 `ServerView` 快照：后端名称、地址、标签、插件元数据。没有负载、健康或容量推断。
- `Servers.register(ServerDefinition)` 返回所有权句柄，支持更新与注销。同名条目由原注册者拥有，不能覆盖其他插件或静态配置。同名重新注册后旧句柄失效。
- `Players.transfer(identity, backendName)` 异步返回 `TransferResult`；`NETWORK_READY` 表示协议交接完成，不表示玩法数据加载完成。通过插件上下文调用时，完成通知也被转移到 I/O 线程之外。

插件先自行完成岛屿加载或实例唤醒，再调用转服。发现只是注册后端的一种来源，见[发现插件](discovery.md)。

## 初始选服

`Plugin.initialPlacementHandler()` 返回 `Optional<InitialPlacementHandler>`。整个代理最多有一个初始选服处理器，它收到 `PlayerView` 与当前 `List<ServerView>`，异步返回 `PlacementDecision.select(name)` 或 `reject(reason)`。未提供处理器时，核心选择目录中注册顺序的第一个后端。

访问检查全部通过后才调用选服。`plugins.initialPlacementTimeoutSeconds` 默认 15 秒；数据库查询和加载等待必须在期限内完成。失败或超时结束本次登录，后续登录仍可调用该插件。断线会取消未决请求，尽力取消插件返回的 future，并移除尚未运行的回调。

## 玩家命令

在 `onLoad` 或 `onEnable` 注册根命令：

```java
context.commands().register("where", command -> {
    command.reply("当前服务器：" + command.player().currentServer().orElse("未连接"));
});
```

名称不区分大小写且全局唯一；别名可分别注册。返回的 `CommandRegistration.close()` 可撤销该命令，插件停用时自动撤销。`CommandInvocation` 包含 `player()`、实际调用的 `name()`、未拆词的 `arguments()` 和纯文本 `reply(String)`；异步完成后可继续使用 invocation 回复原连接，已断线的连接不会收到回复。

只消费已注册的 `/命令`，普通聊天和未知命令原样交给后端。权限判断属于插件：必须先检查调用者权限再执行 `/ban` 等管理操作。命令回调使用独立有界工作池，异常返回通用失败信息，队列满时告知玩家稍后重试。

每位玩家可突发调用 10 次代理命令，之后每 200 毫秒恢复一次额度；超额命令仍由代理消费，限速提示每两秒至多一次。回复最多 1024 个 Java 字符，单会话至多挂起 64 条；连接不可写或已离线时丢弃回复。普通转服期间仍可调用代理命令。

## 玩家 / IP 访问检查

访问控制需要两个不同时间点的数据，因此提供两个可选钩子：

| 钩子 | 调用时机 | 输入 | 拒绝效果 |
| --- | --- | --- | --- |
| `Plugin.connectionCheck()` | TCP 建连后、读取 Minecraft 协议前，包括状态查询连接 | 代理实际观察到的 `InetSocketAddress` | 关闭 TCP；客户端尚未进入登录协议，无法显示理由 |
| `Plugin.loginCheck()` | 在线身份验证成功或离线身份生成后、初始选服及后端连接前 | `LoginRequest` | 发送带理由的 Login Disconnect 后关闭；在线模式下消息经过加密 |

两种钩子均返回 `CompletionStage<AccessDecision>`，使用 `AccessDecision.allow()` 或 `AccessDecision.deny(reason)`。拒绝理由是 1–1024 个 Unicode 码点的非空纯文本，由核心转义为聊天 JSON。返回空 stage、空结果、抛出异常、超时、工作队列或未决请求达到上限时都拒绝本次连接；内部错误不会作为详细异常发给客户端。

`LoginRequest.player()` 包含本次连接身份、用户名和空的 `currentServer()`。`remoteAddress()` 来自 TCP socket，不使用客户端握手中填写的地址、UUID 或任何转发头。若代理前面还有网络中继，这里看到的是中继的地址。`authenticated()` 在 `ONLINE_BUNGEE` 会话校验成功后为 true；OFFLINE 为 false，其用户名与推导 UUID 不能当作经过认证的账号。

多个插件按加载顺序逐个检查；只有全部允许才继续，任一拒绝后不再调用后续检查，不存在能撤销拒绝的“允许”事件。整个阶段共享 `plugins.accessTimeoutSeconds`（默认 5，范围 1–30 秒），不是每个插件另得一份期限。TCP 检查与登录检查分别计时，不占用初始选服的期限。

宿主使用独立、有界的访问工作池，默认队列容量 128、两个阶段合计最多 1024 个未决请求。超时与客户端断线会撤销排队任务，并尽力取消已经返回的插件 future；插件自己的远端请求还应设独立超时。TCP 检查期间最多暂存 4 KiB 数据，超限关闭；检查允许后移除该拦截器。没有配置对应钩子时跳过该阶段，不调度工作线程。连接访问检查不会在 PLAY 转发、命令执行或转服时重复触发。

### Ban 插件示例

下面是插件内的字段和钩子示例。插件自行从持久存储填充或更新这些并发缓存；核心不保存封禁名单，也不定义封禁期限、申诉或管理权限。

```java
private final Set<InetAddress> bannedIps = ConcurrentHashMap.newKeySet();
private final Map<UUID, String> playerBans = new ConcurrentHashMap<>();

@Override
public Optional<ConnectionCheck> connectionCheck() {
    return Optional.of(peer -> CompletableFuture.completedFuture(
        bannedIps.contains(peer.getAddress())
            ? AccessDecision.deny("IP 已被封禁")
            : AccessDecision.allow()));
}

@Override
public Optional<LoginCheck> loginCheck() {
    return Optional.of(login -> {
        // 此例要求真实账号身份；离线环境需明确制定自己的身份策略。
        if (!login.authenticated()) {
            return CompletableFuture.completedFuture(
                AccessDecision.deny("此服务要求账号认证"));
        }
        String reason = playerBans.get(login.player().identity().playerId());
        return CompletableFuture.completedFuture(
            reason == null ? AccessDecision.allow() : AccessDecision.deny(reason));
    });
}
```

若希望 IP 封禁也显示理由，可以只实现 `loginCheck()`，在其中用 `remoteAddress().getAddress()` 查询 IP 封禁；这样检查发生在认证之后。需要查异步数据库时，直接组合数据库返回的 stage，例如：

```java
return Optional.of(login -> bans.findReason(login.player().identity().playerId())
    .thenApply(reason -> reason.map(AccessDecision::deny).orElseGet(AccessDecision::allow)));
```

这里 `bans.findReason` 是插件自己的方法，返回 `CompletionStage<Optional<String>>`。查询失败会使该次登录失败，宿主不会把查询异常当作“未封禁”。如果插件选择缓存或故障时的其他策略，需由插件明确实现。

这组钩子控制新连接和新登录。更新封禁名单不会自动踢出已经在线的玩家；当前 `Players` API 没有踢出接口。不会在转服时重复进行账号/IP 检查。

## 为什么采用这些钩子

当前需求是两个可等待、有最终允许/拒绝结果的接入检查。明确类型的可选钩子复用现有插件生命周期，无需反射、注解扫描、事件优先级或可变取消状态；等待、超时和关闭由宿主统一处理。PLAY 转发路径因此不需要经过通用事件派发器。这里只说明结构上的取舍，尚未对接入吞吐和真实数据库延迟作性能承诺。

源码入口：[`Plugin`](../proxy-plugin-api/src/main/java/dev/strataproxy/api/Plugin.java)、[`AccessDecision`](../proxy-plugin-api/src/main/java/dev/strataproxy/api/AccessDecision.java)、[`LoginRequest`](../proxy-plugin-api/src/main/java/dev/strataproxy/api/LoginRequest.java)。

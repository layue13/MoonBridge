# 插件开发

插件依赖 `proxy-plugin-api`，不依赖 `proxy-core` 或 Netty。入口实现 `dev.strataproxy.api.Plugin`，提供公开无参构造器，在 JAR 的 `META-INF/services/dev.strataproxy.api.Plugin` 中声明实现类，并由配置中的 `plugins.enabled` 显式启用。

## 生命周期和线程

`onLoad(PluginContext)` 获得 `players()`、`servers()`、`commands()`、`events()`、SLF4J `logger()` 和不可变 `settings()`。宿主在所有插件加载后调用 `onEnable()`；插件全部启用成功后、监听器启动前冻结事件订阅。事件订阅只能在 `onLoad` 或 `onEnable` 注册，运行期间可以随时撤销。初始化失败会终止启动并回收已创建的注册。

事件通过 `PluginContext.events()` 提供的 `dev.strataproxy.api.event.Events` 订阅。访问事件使用 `AccessListener`，返回 `CompletionStage<AccessDecision>`；通知事件使用 `Consumer`，不返回处理结果。数据库客户端、缓存和外部任务的生命周期由插件管理；返回 `CompletionStage` 表达异步结果，避免在回调里 `join()` 等待另一个任务。各连接的访问检查可以并发，插件共享数据必须支持并发访问。

关闭或插件失败时，宿主移除该插件的事件订阅、后端与命令注册。关闭时，宿主先终止未决访问请求并停止接收新回调，再逆序调用 `onDisable()`。所有关闭钩子共享 10 秒期限；插件忽略中断时仍可能继续运行，因此插件必须自行停止数据库/调度器等资源。随后关闭类加载器。当前没有插件热卸载或热加载入口。

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

## 类型化事件

`PluginContext.events()` 返回 `dev.strataproxy.api.event.Events`。访问决策和通知使用不同的订阅重载：

```java
<E extends NotificationEvent> EventSubscription subscribe(
    Class<E> type, Consumer<? super E> listener);

<E extends AccessEvent> EventSubscription subscribe(
    Class<E> type, AccessListener<? super E> listener);
```

通知监听器消费事件；访问监听器的 `onEvent` 返回 `CompletionStage<AccessDecision>`。订阅使用具体事件类，不支持用 `AccessEvent.class` 或 `NotificationEvent.class` 监听所有事件。订阅仅允许在插件 `onLoad` 或 `onEnable` 阶段注册，宿主在启动监听器前冻结注册。`EventSubscription.close()` 可在任何时候调用且幂等；插件失败或停用时订阅自动移除。撤销会跳过尚未开始的回调，已经开始的异步访问 stage 可以结束。

### 连接与登录访问决策

`ConnectionEvent(remoteAddress)` 在 TCP 建连后、Minecraft 协议解析或认证前触发，适用于来源地址策略，包括状态查询连接。拒绝或检查失败时直接关闭 TCP，因此客户端尚无 Login Disconnect 可显示。`LoginEvent(player, remoteAddress, authenticated)` 在身份建立后、初始选服和后端连接前触发；拒绝会发送 Login Disconnect。`remoteAddress` 来自代理看到的 TCP socket。若代理前有网络中继，地址是该中继地址。`authenticated` 在 `ONLINE_BUNGEE` 会话验证成功后为 true；OFFLINE 身份未认证。

多个订阅者按注册顺序执行，首个拒绝即停止后续访问回调。空 stage、空结果、异常、超时或过载均按拒绝处理；内部错误不会把详细异常发送给客户端。访问引擎使用有界队列，容量 128，最多 1024 个未决请求；`plugins.accessTimeoutSeconds` 默认 5 秒，范围 1–30 秒。连接检查与登录检查各自计时，不占用初始选服期限。连接检查期间最多暂存 4 KiB 数据，超限关闭；无人订阅的事件不分配/派发访问工作（实现允许时）。访问事件只在接入和登录阶段触发，不进入 PLAY 包热路径。

```java
context.events().subscribe(ConnectionEvent.class, event ->
    CompletableFuture.completedFuture(
        bannedIps.contains(event.remoteAddress().getAddress())
            ? AccessDecision.deny("IP 已被封禁")
            : AccessDecision.allow()));

context.events().subscribe(LoginEvent.class, event -> {
    if (!event.authenticated()) {
        return CompletableFuture.completedFuture(
            AccessDecision.deny("此服务要求账号认证"));
    }
    String reason = playerBans.get(event.player().identity().playerId());
    return CompletableFuture.completedFuture(reason == null
        ? AccessDecision.allow() : AccessDecision.deny(reason));
});
```

插件自行维护封禁数据与权限策略；核心不保存封禁名单，也不定义封禁期限或申诉流程。数据库异步查询可直接返回其 stage；例如用以下订阅替换内存名单检查（`bans.findReason` 是插件自己的方法，返回 `CompletionStage<Optional<String>>`）：

```java
context.events().subscribe(LoginEvent.class, event ->
    bans.findReason(event.player().identity().playerId())
        .thenApply(reason -> reason.map(AccessDecision::deny).orElseGet(AccessDecision::allow)));
```

异步例子的身份策略仍由插件明确制定，查询失败会使该次访问失败。更新封禁名单不会自动踢出在线玩家，当前 `Players` API 没有踢出接口。访问策略不会在转服时重复执行。

### 会话通知

`ServerConnectedEvent(player, previousServer)` 在在线玩家首次发布（后端登录校验后，此时 `previousServer` 为空）以及转服提交完成时触发（此时携带原后端名称）。它表示代理会话已发布/切换，不承诺 Forge 或玩法世界已经就绪。`PlayerDisconnectedEvent(player)` 对每个曾经发布的玩家，在会话关闭时恰好触发一次；被拒绝的登录从未发布，不产生此事件。

通知在单个有界 FIFO 工作线程上运行，队列容量 128，同一玩家的通知按顺序交付。监听异常相互隔离；队列过载时尽力记录并丢弃，连接会话不等待通知。通知不是可靠审计或计费通道。没有订阅者时尽量跳过事件分配和派发。插件不能自行发布核心事件。

```java
context.events().subscribe(ServerConnectedEvent.class, event -> {
    String previous = event.previousServer().orElse("首次连接");
    context.logger().info("{} 已连接到 {}（来自 {}）",
        event.player().username(), event.player().currentServer().orElse("未知"), previous);
});

context.events().subscribe(PlayerDisconnectedEvent.class, event ->
    context.logger().info("{} 已断开", event.player().username()));
```

`InitialPlacementHandler` 仍是单提供者选服策略。命令、服务器目录和转服 API 保持原有职责，不通过事件替代。

类型化事件为访问决策和会话通知提供统一订阅入口，同时保留访问控制需要的顺序、异步结果和失败关闭规则。事件系统本身不意味着代理会变慢：没有 PLAY 包事件，访问检查仅运行于连接/登录阶段，通知也不阻塞会话。但目前没有事件 API 的专门基准，不能据此宣称性能收益或无开销。

源码入口：[`PluginContext`](../proxy-plugin-api/src/main/java/dev/strataproxy/api/PluginContext.java)、[`Events`](../proxy-plugin-api/src/main/java/dev/strataproxy/api/event/Events.java)、[`AccessDecision`](../proxy-plugin-api/src/main/java/dev/strataproxy/api/AccessDecision.java)。

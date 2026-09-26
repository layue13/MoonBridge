# 插件开发

插件依赖 `proxy-plugin-api`，不依赖 `proxy-core` 或 Netty。入口实现 `dev.strataproxy.api.Plugin`，提供公开无参构造器，在 JAR 的 `META-INF/services/dev.strataproxy.api.Plugin` 中声明实现类，并由配置中的 `plugins.enabled` 显式启用。

## 生命周期和线程

`onLoad(PluginContext)` 获得 `players()`、`servers()`、`commands()`、`events()`、SLF4J `logger()` 和不可变 `settings()`。宿主在所有插件加载后调用 `onEnable()`；插件全部启用成功后、监听器启动前冻结事件订阅。事件订阅只能在 `onLoad` 或 `onEnable` 注册，运行期间可以随时撤销。初始化失败会终止启动并回收已创建的注册。

事件通过 `PluginContext.events()` 提供的 `dev.strataproxy.api.event.Events` 订阅。所有事件都使用同一 sealed `Event<R>`、`EventListener<E, R>` 和 `Events.subscribe` 方法；监听器返回 `CompletionStage<R>`。无结果事件使用 `Event<Void>`，准入事件返回 `AccessDecision`。数据库客户端、缓存和外部任务的生命周期由插件管理；返回 `CompletionStage` 表达异步结果，避免在回调里 `join()` 等待另一个任务。各连接的准入检查可以并发，插件共享数据必须支持并发访问。

关闭或插件失败时，宿主移除该插件的事件订阅、后端与命令注册。关闭时，宿主先终止未决访问请求并停止接收新回调，再逆序调用 `onDisable()`。所有关闭钩子共享 10 秒期限；插件忽略中断时仍可能继续运行，因此插件必须自行停止数据库/调度器等资源。随后关闭类加载器。当前没有插件热卸载或热加载入口。

## 玩家与后端

- `Players.online()` 和 `find(PlayerIdentity)` 返回不可变 `PlayerView`。`PlayerIdentity` 同时包含玩家 UUID 和本次连接代次；异步转服要保留这个完整身份，不能用旧连接去操作重连后的玩家。
- `Servers.all()` / `find(name)` 返回 `ServerView` 快照：后端名称、地址、标签、插件元数据。没有负载、健康或容量推断。
- `Servers.register(ServerDefinition)` 返回所有权句柄，支持更新与注销。同名条目由原注册者拥有，不能覆盖其他插件或静态配置。同名重新注册后旧句柄失效。
- `Players.transfer(identity, backendName)` 异步返回 `TransferResult`；`NETWORK_READY` 表示协议交接完成，不表示玩法数据加载完成。通过插件上下文调用时，完成通知也被转移到 I/O 线程之外。

插件先自行完成岛屿加载或实例唤醒，再调用转服。动态后端可通过[后端控制通道](backend-channel-design.md)注册。

### 后端控制消息

`PluginContext.backendChannels()` 让代理插件与已注册的后端实例双向通信。后端侧使用独立的 Java 8 `backend-channel-client` SDK，不依赖玩家在线。代理插件在 `onLoad` 或 `onEnable` 调用 `subscribe("命名空间:操作", handler)`；同一通道只允许一个处理器，停用时自动撤销。处理器收到的 `BackendMessage` 含已认证的实例身份和连接代次，返回 `CompletionStage<byte[]>` 作为请求响应。

`send(backendName, channel, payload)` 返回 `SENT`、`NOT_CONNECTED` 或 `BACKPRESSURED`；`SENT` 只表示消息写入控制连接。`request(...)` 在五秒内等待后端处理结果，断线、无处理器或超时会异常完成。负载在提交时复制，单条最多 64 KiB；回调在有界工作池运行，不应同步等待数据库或网络任务。配置与交付边界见[后端控制通道](backend-channel-design.md)。

### 消息与主动断开

`Players.sendMessage(identity, text)` 和 `disconnect(identity, reason)` 均绑定完整的 `PlayerIdentity`，可从任意线程调用；通过插件上下文获取的 stage 在 I/O 线程外完成。参数错误立即报告，插件停用后拒绝继续提交。

- 消息是纯文本，最多 1024 个 Unicode 码点。返回 `MessageResult.SENT` 表示网络写入完成；连接消失或正在关闭返回 `NOT_CONNECTED`；登录、初次 Forge 握手未就绪或转服交接期间返回 `NOT_READY`；不可写或该连接已有 64 条未完成消息返回 `BACKPRESSURED`。网络写失败以异常完成。
- 主动断开要求非空白原因，最多 1024 个 Unicode 码点。核心按实际 LOGIN/PLAY 阶段发包，最长等待 5 秒后清理连接、未决转服和候选后端。返回 `DisconnectResult.DISCONNECTED` 表示连接清理完成，原因文本仅尽力交付；身份已失效返回 `NOT_CONNECTED`。重复断开合并处理，取消调用方等待不撤销已提交的断开。
- `disconnect` 也支持准入或初始选服回调中的已识别玩家，此时玩家还未出现在 `online()` 中。

```java
// Ban 数据和权限检查由插件先完成；保存完整 identity，防止误操作重连者。
context.players().disconnect(player.identity(), "你已被封禁，请联系管理员。");

context.players().sendMessage(player.identity(), "目的地已准备好。").thenAccept(result -> {
    if (result == MessageResult.SENT) {
        context.logger().debug("消息已写入玩家连接");
    }
});
```

完整状态与验收设计见[玩家操作](player-operations.md)。

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

每位玩家可突发调用 10 次代理命令，之后每 200 毫秒恢复一次额度；超额命令仍由代理消费，限速提示每两秒至多一次。`reply` 复用玩家消息实现：最多 1024 个 Unicode 码点，与主动消息共享每连接 64 条未完成写入上限；不可写、已离线或协议过渡期间丢弃回复。命令回调仍可发起转服。

## 统一类型化事件

`PluginContext.events()` 返回 `dev.strataproxy.api.event.Events`。事件统一使用泛型结果和异步监听器：

```java
sealed interface Event<R> {}

interface EventListener<E extends Event<R>, R> {
    CompletionStage<R> onEvent(E event);
}

<R, E extends Event<R>> EventSubscription subscribe(
    Class<E> type, EventListener<E, R> listener);
```

订阅使用具体事件类，不支持基类全量订阅；准入决策和通知也没有各自的独立监听器或结果 API。订阅只可在插件 `onLoad` 或 `onEnable` 注册，宿主在启动监听器前冻结注册。`EventSubscription.close()` 可随时幂等调用；插件失败或停用时自动移除。撤销会跳过后续尚未被派发线程选中执行的回调；已经选中的回调及其异步 stage 可以继续，关闭订阅不会等待它们完成。

### 玩家准入

`ConnectionAdmissionEvent(InetSocketAddress remoteAddress)` 在 TCP 建连后、Minecraft 协议解析前触发，适用于来源地址策略，包括状态查询连接。地址是代理接受连接时看到的实际 TCP socket peer；若代理前有网络中继，地址就是该中继。拒绝或准入检查失败时直接关闭 TCP，因此客户端尚无 Login Disconnect 可显示。`PlayerAdmissionEvent(PlayerView player, InetSocketAddress remoteAddress, boolean authenticated)` 在身份建立后、初始选服和后端登录前触发。在线代理模式下，核心仍先执行 `ONLINE_BUNGEE` 会话验证；事件在验证成功后派发，`authenticated` 为 `true`。离线模式在生成客户端名字和离线 UUID 后派发，`authenticated` 为 `false`。因此该事件可供 Ban、白名单等准入策略使用，不能替代认证提供者或改变核心认证过程。拒绝会发送 Login Disconnect。

多个准入监听器按注册顺序运行；允许策略运行全部监听器，首个拒绝即停止并拒绝玩家。空 stage、空结果、异常、超时或过载均失败关闭；内部错误不会把详细异常发送给客户端。准入请求使用有界队列，容量 128，最多 1024 个未决请求。`plugins.eventTimeoutSeconds` 默认 5 秒，范围 1–30 秒；每个准入事件的所有监听器共享同一个事件期限。准入事件只在接入和登录阶段触发，不进入 PLAY 包热路径。

```java
context.events().subscribe(PlayerAdmissionEvent.class, event -> {
    String reason = playerBans.get(event.player().identity().playerId());
    return CompletableFuture.completedFuture(reason == null
        ? AccessDecision.allow() : AccessDecision.deny(reason));
});
```

插件自行维护封禁数据与权限策略；核心不保存封禁名单，也不定义封禁期限或申诉流程。异步数据库查询可以直接返回 stage；查询异常或超时会使该次准入失败。更新封禁名单后，插件可调用 `Players.disconnect` 踢出对应的在线连接。玩家准入策略不会在转服时重复执行。

### 会话通知

`ServerConnectedEvent(player, previousServer)` 在在线玩家首次发布（后端登录校验后，此时 `previousServer` 为空）以及转服提交完成时触发（此时携带原后端名称）。它表示代理会话已发布/切换，不承诺 Forge 或玩法世界已经就绪。`PlayerDisconnectedEvent(player)` 对每个曾经发布的玩家，在会话关闭时恰好触发一次；被拒绝的登录从未发布，不产生此事件。

通知使用同一异步监听器 API 和 `CompletionStage<Void>` 结果契约。通知按 FIFO 派发，每次最多一个活动事件和 128 个排队事件，等待当前事件的监听器链完成后再派发下一个。监听器异常相互隔离；`Void` 事件以 `null` 完成。事件期限从进入派发队列时开始计算；超过 `plugins.eventTimeoutSeconds` 会结束该事件并继续队列。代理会话不等待通知，过载时尽力丢弃并在 I/O 线程外聚合记录；通知不是可靠审计或计费通道。没有订阅者时尽量跳过事件分配和派发。插件不能自行发布核心事件。

```java
context.events().subscribe(ServerConnectedEvent.class, event -> {
    String previous = event.previousServer().orElse("首次连接");
    context.logger().info("{} 已连接到 {}（来自 {}）",
        event.player().username(), event.player().currentServer().orElse("未知"), previous);
    return CompletableFuture.completedFuture(null);
});

context.events().subscribe(PlayerDisconnectedEvent.class, event ->
    {
        context.logger().info("{} 已断开", event.player().username());
        return CompletableFuture.completedFuture(null);
    });
```

需要异步完成时，可订阅同一通知事件并返回 stage：

```java
context.events().subscribe(PlayerDisconnectedEvent.class, event ->
    auditQueue.enqueue(event.player().identity()).thenApply(ignored -> null));
```

`InitialPlacementHandler` 仍是单提供者选服策略。命令、服务器目录和转服 API 保持原有职责，不通过事件替代。

统一事件模型让准入决策和会话通知共享同一类型、监听器、订阅表与派发实现，同时保留各类事件所需的运行策略。准入阶段执行有序且失败关闭的结果聚合；生命周期通知异步 FIFO 尽力交付并与会话处理隔离。没有 PLAY 包事件。目前没有事件 API 的专门基准，不能据此宣称性能收益或无开销。

源码入口：[`PluginContext`](../proxy-plugin-api/src/main/java/dev/strataproxy/api/PluginContext.java)、[`Events`](../proxy-plugin-api/src/main/java/dev/strataproxy/api/event/Events.java)、[`AccessDecision`](../proxy-plugin-api/src/main/java/dev/strataproxy/api/AccessDecision.java)。

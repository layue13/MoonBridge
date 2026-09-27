# 插件开发

插件依赖 `proxy-plugin-api`，不依赖 `proxy-core` 或 Netty。入口实现 `dev.moonbridge.api.Plugin`，提供公开无参构造器，在 JAR 的 `META-INF/services/dev.moonbridge.api.Plugin` 中声明实现类，并由配置中的 `plugins.enabled` 显式启用。

## 生命周期和线程

`onLoad(PluginContext)` 获得 `players()`、`servers()`、`commands()`、`permissions()`、`events()`、`messaging()`、专属 `dataDirectory()`、SLF4J `logger()` 、不可变 `settings()` 和本代理进程的 `proxyEpoch()`（每次启动重新生成，与后端会话绑定证明一致）。宿主在所有插件加载后调用 `onEnable()`；插件全部启用成功后、监听器启动前冻结事件订阅并确定唯一权限提供者。事件订阅只能在 `onLoad` 或 `onEnable` 注册，运行期间可以随时撤销。初始化失败会终止启动并回收已创建的注册。

事件通过 `PluginContext.events()` 提供的 `dev.moonbridge.api.event.Events` 订阅。所有事件都使用同一 sealed `Event<R>`、`EventListener<E, R>` 和 `Events.subscribe` 方法；监听器返回 `CompletionStage<R>`。无结果事件使用 `Event<Void>`，准入事件返回 `AccessDecision`。数据库客户端、缓存和外部任务的生命周期由插件管理；返回 `CompletionStage` 表达异步结果，避免在回调里 `join()` 等待另一个任务。各连接的准入检查可以并发，插件共享数据必须支持并发访问。

关闭或插件失败时，宿主移除该插件的事件订阅、后端与命令注册。关闭时，宿主先终止未决访问请求并停止接收新回调，再逆序调用 `onDisable()`。所有关闭钩子共享 10 秒期限；插件忽略中断时仍可能继续运行，因此插件必须自行停止数据库/调度器等资源。随后关闭类加载器。当前没有插件热卸载或热加载入口。

## 玩家与后端

- `Players.online()` 和 `find(PlayerIdentity)` 返回不可变 `PlayerView`。`PlayerIdentity` 同时包含玩家 UUID 和本次连接代次；异步转服要保留这个完整身份，不能用旧连接去操作重连后的玩家。
- `Servers.all()` / `find(name)` 返回 `ServerView` 快照：后端名称、地址、标签、插件元数据。没有负载、健康或容量推断。
- `Servers.register(ServerDefinition)` 返回所有权句柄，支持更新与注销。同名条目由原注册者拥有，不能覆盖其他插件或静态配置。同名重新注册后旧句柄失效。
- `Players.transfer(identity, backendName)` 异步返回 `TransferResult`；`NETWORK_READY` 表示协议交接完成，不表示玩法数据加载完成。通过插件上下文调用时，完成通知也被转移到 I/O 线程之外。

插件先自行完成岛屿加载或实例唤醒，再调用转服。动态后端可通过[后端控制通道](backend-channel-design.md)注册。

### 后端控制消息

`PluginContext.messaging().channel("命名空间:通道")` 提供与后端相同的消息 API，不依赖玩家在线。`subscribe` 注册通知订阅者，`onRequest` 注册唯一请求处理器；每个插件持有独立 scope，停用时自动撤销。处理器收到不可变 `Message`，其中包含消息 UUID、认证来源、目标和回复关联；请求处理器返回 `CompletionStage<byte[]>`，框架创建完整的回复消息。

`send(Endpoint.backend(name), payload)` 返回含消息 ID 的投递回执，`ACCEPTED` 表示目标消息服务接受处理任务；`request(...)` 返回完整回复消息，默认期限五秒；`publish` 返回各候选节点的投递结果。负载在提交时复制，单条最多 64 KiB；回调在有界工作池运行，不应同步等待数据库或网络任务。后端插件通过唯一的 Bukkit 宿主服务获取相同 API，不自行创建连接。旧 `backendChannels()` 已移除。完整配置、Java 8 宿主安装与故障语义见[后端 Channel 消息服务](backend-channel-design.md)。

### 消息与主动断开

`Players.sendMessage(identity, text)` 和 `disconnect(identity, reason)` 均绑定完整的 `PlayerIdentity`，可从任意线程调用；通过插件上下文获取的 stage 在 I/O 线程外完成。参数错误立即报告，插件停用后拒绝继续提交。

- 消息支持 Adventure Component；String 入口为纯文本，最多 1024 个 Unicode 码点。返回 `MessageResult.SENT` 表示网络写入完成；连接消失或正在关闭返回 `NOT_CONNECTED`；登录、初次 Forge 握手未就绪或转服交接期间返回 `NOT_READY`；不可写或该连接已有 64 条未完成消息返回 `BACKPRESSURED`。网络写失败以异常完成。
- 主动断开接受非空白的 Component 原因；String 入口最多 1024 个 Unicode 码点。核心按实际 LOGIN/PLAY 阶段发包，最长等待 5 秒后清理连接、未决转服和候选后端。返回 `DisconnectResult.DISCONNECTED` 表示连接清理完成，原因文本仅尽力交付；身份已失效返回 `NOT_CONNECTED`。重复断开合并处理，取消调用方等待不撤销已提交的断开。
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

初始选服是登录策略：访问检查通过后，代理必须先确定一个或多个有序目标，再建立后端连接。后端注册只表示目标当前可被解析，不代表它适合作为新玩家入口；代理不根据注册顺序、标签或推测出的健康状态选服。

没有插件接管时，`initialRouting.servers` 是明确配置的候选名称列表，按顺序尝试，最多 16 个且不得重名或为空白。默认空列表是 fail-closed：没有目标时拒绝本次登录，并向玩家说明没有可用的初始服务器。只由插件提供选服策略的配置可以保留空列表。候选名称在每次尝试时从当前服务器目录重新解析；不存在、已注销或连接期间被替换的条目会跳过，代理不会将旧地址用于新注册者。配置示例：

```yaml
initialRouting:
  servers: [lobby-a, lobby-b]
  timeoutSeconds: 15
```

`Plugin.initialPlacementHandler()` 返回 `Optional<InitialPlacementHandler>`。整个代理最多有一个处理器；它收到 `PlayerView` 和当前 `List<ServerView>` 快照，并异步返回 `PlacementDecision.select(name)`、按优先级依次尝试的 `select(List<String>)`，或 `reject(reason)`。只要处理器存在，它的选择完全接管初始路由，配置列表不会作为隐式备用目标；显式拒绝、异常、空结果或期限届满都结束本次登录。选择后目录会重新解析，因此插件异步等待实例启动时可以在其注册后返回名称。代理不会轮询目录、唤醒实例或偷偷改投默认大厅；实例编排由该插件负责。

`initialRouting.timeoutSeconds` 默认 15 秒，范围 1–120 秒。一个总期限覆盖插件的异步选服和所有候选目标的 DNS/TCP 建连。只有 DNS/TCP 连接失败、且 Minecraft 握手或登录字节尚未发送时，才可尝试下一个候选；TCP 成功后会再次核对注册句柄仍有效，再发送协议数据。开始后端握手/登录后发生拒绝、断开或超时都以本次登录失败结束，不重放 Minecraft 或 Forge 登录流量。完成后端 TCP 建连后的登录阶段仍使用既有的 15 秒期限。

断线会取消未决选服，尽力取消插件返回的 future，并移除尚未运行的回调。插件应及时完成其阶段；不得通过阻塞调用等待数据库、实例启动或网络操作。

## 玩家命令

在 `onLoad` 或 `onEnable` 注册根命令：

```java
context.commands().register("where", command -> {
    command.reply("当前服务器：" + command.player().currentServer().orElse("未连接"));
});
```

名称不区分大小写且全局唯一；别名可分别注册。返回的 `CommandRegistration.close()` 可撤销该命令，插件停用时自动撤销。`CommandInvocation` 包含 `player()`、实际调用的 `name()`、未拆词的 `arguments()` 和纯文本 `reply(String)`；异步完成后可继续使用 invocation 回复原连接，已断线的连接不会收到回复。

只消费已注册的 `/命令`，普通聊天和未知命令原样交给后端。通过 `register(name, permission, handler)` 声明权限节点后，核心统一检查执行与补全；拒绝的已注册命令仍被代理消费。原有不带权限节点的注册表示公开玩家命令，复杂子命令可通过 `context.permissions()` 查询各自权限。控制台命令需用 `CommandRegistrationOptions` 显式启用，并从 `invocation.source()` 读取来源。详见[玩家权限与 LuckPerms](permissions.md)。命令回调使用独立有界工作池，异常返回通用失败信息，队列满时告知玩家稍后重试。

每位玩家可突发调用 10 次代理命令，之后每 200 毫秒恢复一次额度；超额命令仍由代理消费，限速提示每两秒至多一次。`reply` 复用玩家消息实现：最多 1024 个 Unicode 码点，与主动消息共享每连接 64 条未完成写入上限；不可写、已离线或协议过渡期间丢弃回复。命令回调仍可发起转服。

## 统一类型化事件

`PluginContext.events()` 返回 `dev.moonbridge.api.event.Events`。事件统一使用泛型结果和异步监听器：

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

源码入口：[`PluginContext`](../proxy-plugin-api/src/main/java/dev/moonbridge/api/PluginContext.java)、[`Events`](../proxy-plugin-api/src/main/java/dev/moonbridge/api/event/Events.java)、[`AccessDecision`](../proxy-plugin-api/src/main/java/dev/moonbridge/api/AccessDecision.java)。

富文本构造、MiniMessage 和异步命令补全示例见[消息与命令补全](messages-and-completion.md)。

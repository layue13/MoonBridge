# 玩家权限与 LuckPerms

MoonBridge 向业务插件提供 `PluginContext.permissions()`，向权限插件提供 `PermissionProvider`。业务插件依赖 `proxy-plugin-api`，不需要依赖 LuckPerms 的类或数据库。玩家权限与后端注册认证、Channel 命名空间 ACL 是独立的安全边界。

## 业务插件查询

```java
import dev.moonbridge.api.permission.PermissionContext;
import dev.moonbridge.api.permission.PermissionResult;

boolean allowed = context.permissions().check(player.identity(), "example.enter")
        == PermissionResult.ALLOW;

// 便捷方法：只有 ALLOW 返回 true。
boolean mayEnter = context.permissions().hasPermission(player.identity(), "example.enter");

// 查询拟前往的后端，而不改变玩家当前所在的后端。
boolean mayEnterLobby = context.permissions().check(player.identity(), "example.enter",
        PermissionContext.empty().with("backend", "lobby")) == PermissionResult.ALLOW;
```

`PlayerIdentity` 包含 UUID 与连接代次。过期连接、未就绪的权限数据、没有提供者、提供者出错或宿主关闭，都返回 `UNAVAILABLE`。提供者正常查询返回 `ALLOW`、`DENY` 或 `UNDEFINED`；`UNDEFINED` 只表示没有匹配的权限规则，不能表示加载失败。安全操作应当只接受 `ALLOW`。

上下文是不可变的多值集合。核心从当前连接快照提供 `backend=<当前后端名称>`；尚未选服时没有此项。显式查询上下文替换同名键的全部默认值，空集合表示清除此键，未指定的键保持默认。一次查询不会改变连接状态。上下文由可信代理插件提供，不能直接采用客户端自报的权限或上下文。

所有检查使用已加载的本地缓存，不执行网络或数据库 I/O。业务插件可以在玩家准入事件和初始选服回调中查询权限，此时核心已经完成该连接的权限准备。转服操作本身不会隐式检查某个固定权限节点：发起操作的命令、选服策略或业务插件负责决定操作者是否有权执行这项业务。

## 声明命令权限

```java
context.commands().register("island", "example.command.island", invocation -> {
    invocation.reply("已通过命令权限检查。");
});
```

核心统一过滤命令根名称与参数补全，并在执行前检查权限。拒绝、未设置和不可用均不能执行有权限要求的命令；已注册但被拒绝的命令不会继续转发给 backend。后端补全返回同名受限代理命令时也会被过滤。异步补全在交付前重新检查权限。

原有不带权限参数的注册方法表示公开的玩家命令；请勿用它注册管理命令而遗漏权限检查。复杂子命令可以在处理器内按动作查询不同节点。支持控制台的处理器通过 `CommandRegistrationOptions` 显式启用控制台，使用 `invocation.source()` 判断来源；旧的 `invocation.player()` 只适用于玩家。控制台是可信的本地管理入口，不会伪装成某个玩家 UUID。

耗时的异步命令可用 `registerAsync` 返回完成阶段；核心不会占用命令工作线程等待它。`Commands.execute(source, command)` 在处理器的完成阶段结束后才完成，适用于需要等待嵌套命令的适配器。业务插件应组合完成阶段，避免在同步处理器中阻塞等待同一工作池的其他命令。

代理插件是受信任的 JVM 代码。`Commands.execute` 允许插件显式选择玩家或控制台来源，并重新检查玩家的当前连接与权限；它不证明该命令来自玩家输入，也不构成插件沙箱。面向玩家输入的插件必须保留实际操作者来源，不能把不可信输入提升为控制台命令。

## 实现权限提供者

插件通过 `Plugin.permissionProvider()` 返回一个 `PermissionProvider`。运维在 `plugins.enabled` 明确启用实现插件；最多一个启用的插件可以提供权限。重复提供会使启动失败，不按文件名或加载先后选择赢家。未安装权限插件时可以运行公开功能，但所有需要权限的检查都不可用。

`open(PlayerView)` 返回 `CompletionStage<PermissionSubject>`，为一个准确的连接准备权限数据。核心在有界工作池调用它，位置在身份确认之后、玩家准入与初始选服之前；使用 `plugins.eventTimeoutSeconds` 作为加载期限。异常、超时或容量耗尽会拒绝本次登录。

`PermissionSubject.check(node, context)` 必须快速返回缓存结果。`update(PlayerView)` 在首次进入后端或成功切服时同步更新连接快照，也必须快速返回且不得执行阻塞 I/O。两个方法由核心按同一连接串行调用。提供者应当自行维护缓存失效、权限到期和数据更新。

核心在每条终止路径调用 `close()`，包括准入被拒绝、选服失败、尚未进入后端便断线、正常退出与代理关闭。加载已经超时或玩家已经断线后返回的 subject 仍会被释放。提供者不能仅依赖可丢弃的 `PlayerDisconnectedEvent` 做资源清理；以 UUID 共享数据时还必须处理旧连接清理与新连接加载重叠，避免旧连接驱逐新连接的数据。

实现不得假设 `close()` 总发生在插件停用前：如果自身异步加载没有及时结束，迟到的结果仍会收到清理调用。停止时要关闭自身数据库、调度器和同步服务。当前核心限制同时保留 4096 个权限 subject，以及 1024 个尚未真正完成的加载；超时不会释放未结束加载占用的名额，从而防止失效提供者累积无限异步任务。

## LuckPerms 平台插件

`luckperms-moonbridge` 使用固定版本的官方 LuckPerms 公共核心，并实现 MoonBridge 平台适配。上游源码在 Git 子模块 `vendor/luckperms`，构建前运行：

```sh
git submodule update --init --recursive
```

发行包在 `plugins/` 提供插件 JAR。修改 `config/moonbridge.yml`，替换默认的 `enabled: {}`：

```yaml
plugins:
  directory: "../plugins"
  eventTimeoutSeconds: 5
  enabled:
    dev.moonbridge.luckperms.LuckPermsMoonBridgePlugin:
      proxy-id: "proxy-1"
```

插件数据位于 `plugins/data/dev.moonbridge.luckperms.LuckPermsMoonBridgePlugin/`；LuckPerms 的存储与规则配置使用此目录中的配置文件。不要同时启用另一个权限提供者。

默认存储为本地 H2。首次启动会由 LuckPerms 自身的依赖管理器下载带固定校验值的运行库，缓存到数据目录的 `libs/`；离线部署应先在可联网环境完成启动并带上该缓存。插件 JAR 也作为 `uk.potatolab.moonbridge:luckperms-moonbridge` 发布，版本与同次构建的 MoonBridge API 一致。

组、继承、临时节点、缓存、存储和管理命令由 LuckPerms 引擎处理。MoonBridge 的 `backend` 上下文在适配器中映射到 LuckPerms 的 `world` 上下文；LuckPerms 的 `server` 保留其配置的代理服务器标识，沿用 Velocity 的语义。

玩家使用 `/lp` 或 `/luckperms`；代理控制台使用不带斜杠的同名命令。首次赋权可在控制台运行 `lp user <玩家名或UUID> permission set luckperms.* true`。只给可信管理员此权限。插件内部保留 LuckPerms 的命令权限检查；普通玩家不能通过这些命令为自己提权。

固定的上游 API 枚举没有 MoonBridge 项，因此 LuckPerms 信息页显示 `Standalone - MoonBridge`，并不表示运行的是上游独立服务。平台插件使用隔离的类加载器；业务插件应依赖 MoonBridge 的权限接口，当前不提供跨插件的 `LuckPermsProvider.get()` 服务访问。

同步使用 LuckPerms 原生实现。`messaging-service: auto` 根据共享 SQL 或启用的 Redis 等配置选择消息同步；默认本地 H2 不启用 messenger，`sync-minutes: -1` 不执行周期重载。上游将旧值 `none` 按 `auto` 处理，不应把它当作强制关闭。Velocity 的 `pluginmsg` 不适用于 MoonBridge。

backend 可安装与其 Minecraft / Java 版本兼容的原生 Bukkit LuckPerms，使用相同的身份模式、共享数据库与 LuckPerms 支持的同步方式；MoonBridge 平台插件 JAR 只供代理使用。共享数据库不意味着所有场景的检查结果相同：代理知道后端名称，但不知道玩家在 Bukkit 世界中的位置或其他后端私有上下文。玩家首次登录的权限加载不依赖某个 backend 在线。

接口分层参考 Velocity 的 [PermissionSubject](https://github.com/PaperMC/Velocity/blob/dev/3.0.0/api/src/main/java/com/velocitypowered/api/permission/PermissionSubject.java)、[PermissionProvider](https://github.com/PaperMC/Velocity/blob/dev/3.0.0/api/src/main/java/com/velocitypowered/api/permission/PermissionProvider.java) 和 [PermissionsSetupEvent](https://github.com/PaperMC/Velocity/blob/dev/3.0.0/api/src/main/java/com/velocitypowered/api/event/permission/PermissionsSetupEvent.java)：业务侧查询、提供者计算、登录等待初始化。MoonBridge 使用显式唯一提供者和连接所有权来管理生命周期，并把服务不可用与三态权限值分开。

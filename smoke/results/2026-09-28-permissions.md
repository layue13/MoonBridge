# MoonBridge 权限与 LuckPerms 验收

环境：Windows、Zulu JDK 25、Gradle 9.6.1。实现基于 MoonBridge `0344f0bc65da327c69385ab07c77e69158606d22`，LuckPerms 子模块固定为 `25f223317a9ec2b6e73369126b630eca07d79506`，上游源码未修改。

## 已完成的专项验收

```text
gradlew.bat :luckperms-moonbridge:test :proxy-core:test --tests *Permission* --tests *PluginLibraryTest :proxy-core:permissionAcceptance
BUILD SUCCESSFUL
PERMISSION_ACCEPTANCE_PASS
```

实际运行目录：`proxy-core/build/permission-acceptance/run-2024932596708671230/`。该目录中的 `command-output.txt` 和 `result.txt` 为本机原始输出；构建输出不提交到 Git。

验收从发行包复制真正的 `luckperms-moonbridge` 插件 JAR，经正式 `PluginHost` 的隔离类加载器加载，使用原生 LuckPerms 公共核心和 H2。检查授权、显式拒绝、未设置、组继承、临时权限到期、`world`/`server` 上下文、权限修改后的缓存结果、普通玩家不能自我赋权、原生命令补全、1.7.10 富文本编码、同 UUID 连接交叠、关闭后重新打开数据库和坏配置启动失败清理。进程收集未捕获后台异常，出现异常会判为失败。

专项单元与 TCP 测试还覆盖权限准备先于准入/初始选服、失败和超时拒绝登录、未入服断线清理、迟到结果释放、命令与补全权限重检、插件停用、动态依赖 JAR 的目录约束、同 UUID 用户数据租用，以及异步嵌套命令不占住工作线程。异步命令测试同时验证在途上限、关闭结算和迟到完成幂等。

## 证据边界

完整 `gradlew.bat check :proxy-core:distZip` 已通过：283 个 JUnit 测试，0 失败、0 错误、0 跳过，并运行实际分发包的 H2 验收。实现提交 `7f1980269a041514cf903262c7ebeb5abd2b39f6` 的 [CI 10086](https://git.nest.potatolab.uk:8443/layue13/MoonBridge/actions/runs/10086/jobs/26091) 成功，用时 7 分 44 秒。

翻译修复后的全项目检查与分发包构建再次通过（40 秒，未改动的测试任务复用结果）。最终 H2 证据为 `proxy-core/build/permission-acceptance/run-3128309359661808602/`。本机验收插件 JAR 为 3,814,168 字节，SHA-256：`6ed65c58a3f013725aec08d79c5ba0d70e8eb70c044d8e5a4634802594c47aef`。

## MySQL 与 Redis 外部服务验收

使用 Docker 中隔离的 MySQL 8.4.11、Redis 7.4.11，仅绑定本机回环地址，随机测试凭据保存在忽略的 `build/` 中。没有连接生产数据库。镜像固定证据：

- MySQL：`mysql@sha256:0744ee5ef89ce6ccfa13de3e579fe6b9e27f93dd70da9c06d2c908b1b193fb8d`
- Redis：`redis@sha256:c6eabf748fc7a61dbb5a705c78bcf3d6377b1127a97d0ce965c11c44ba46896f`

`PermissionNetworkAcceptance.java` 在同一 JVM 内启动两个独立 PluginHost、插件 JAR 副本、类加载器和数据目录，连接真正的 MySQL/Redis。两个实例先加载相同 UUID，然后由 A 修改权限，等待 B 已加载的缓存更新；没有调用手工同步命令。每次使用独立 UUID 和组名。

| 场景 | 结果 |
| --- | --- |
| MySQL + `messaging-service: auto` 选择 SQL messenger | PASS |
| MySQL + Redis messenger | PASS |
| 跨实例授权、显式拒绝、撤销、组继承与个人覆盖 | 两种 messenger 均 PASS |
| 不同 `server` 上下文、临时节点到期 | PASS |
| 关闭插件后以新类加载器重新读取 MySQL | PASS |
| MySQL 停机：已有缓存仍可查，新 UUID 准备失败且结果为 UNAVAILABLE | PASS |
| MySQL 恢复：新连接重新加载并写入权限 | PASS |
| Redis 停机：A 写入 MySQL，B 缓存没有即时收到该修改 | PASS |
| Redis 两个订阅重新建立后：新变更同步到 B；重启 B 后仍可读取停机期间及恢复后的两项权限 | PASS |

最终带故障注入的证据目录为：

- `build/permission-network/permission-network-sql-4436507219108060839/`
- `build/permission-network/permission-network-redis-13789818503751059472/`
- 主进程日志：`build/permission-network-sql-outage.log`、`build/permission-network-redis-outage.log`
- 数据库版本、表、行数及故障期间/恢复后写入的具体权限行：`build/permission-services/database-evidence.txt`

最终两轮在插件关闭后等待 500ms，并确认没有未捕获后台异常。预期停机期间的连接失败日志不等同于未捕获异常。测试结束已删除本轮容器和临时卷，移除凭据文件，并对保留的测试配置中的密码进行脱敏。

故障测试暴露并修正了测试工具的两个前提：Docker 随机映射端口可能在 stop/start 后改变，现改为显式绑定一个空闲端口；Redis 可响应 PING 不代表 LuckPerms 已恢复订阅，现以 `PUBSUB NUMSUB luckperms:update` 返回两个订阅者作为恢复后的发消息前置条件。Redis pub/sub 不补发断线窗口的通知，不能据此宣称所有实例在故障期间保持一致。

PowerShell 复现（先完成 `:proxy-core:installDist`，需要 JDK 25、Node.js、Docker）：

```powershell
javac -cp 'proxy-core/build/install/moonbridge/lib/*' -d build/permission-network-classes smoke/PermissionNetworkAcceptance.java
node smoke/permission-services.cjs start
try {
    node smoke/permission-network.cjs sql outage
    if ($LASTEXITCODE -ne 0) { throw 'SQL acceptance failed' }
    node smoke/permission-network.cjs redis outage
    if ($LASTEXITCODE -ne 0) { throw 'Redis acceptance failed' }
    node smoke/permission-services.cjs query
} finally {
    node smoke/permission-services.cjs stop
}
```

测试配置中的无 TLS 连接及 `allowPublicKeyRetrieval` 仅用于隔离的回环数据库，不是部署示例。通用部署配置见 [权限文档](../../docs/permissions.md)。

## Web Editor 完整往返

实际加载分发包的 LuckPerms JAR，用隔离的 H2 测试玩家生成官方 Web Editor 页面，在浏览器中增加 `acceptance.webeditor=true` 并保存，执行页面返回的 `lp applyedits`。断言 MoonBridge 通用权限 API 从 UNDEFINED 变为 ALLOW，并在关闭、重新加载插件后仍为 ALLOW。

结果：`WEB_EDITOR_ACCEPTANCE_PASS mode=applyedits-command`。翻译修复后重新验收的证据目录为 `build/permission-webeditor/web-editor-7249685645443168252/`；浏览器保存成功截图为 `build/permission-webeditor/editor-saved.png`。本次验证显式 applyedits 流程，没有验证信任浏览器后的 WebSocket 自动应用。

手动交互复现：

```powershell
javac -cp 'proxy-core/build/install/moonbridge/lib/*' -d build/permission-network-classes smoke/PermissionWebEditorAcceptance.java
java -cp 'proxy-core/build/install/moonbridge/lib/*;build/permission-network-classes' PermissionWebEditorAcceptance proxy-core/build/install/moonbridge build/permission-webeditor
```

打开输出的编辑器 URL，为 `WebEditorProbe` 添加 `acceptance.webeditor=true`；保存后把 `lp applyedits <code>` 输入该进程。验收有 10 分钟期限。

## 真实 Forge 1.7.10 客户端

`pwsh -NoProfile -File smoke/permission-client.ps1` 返回 `REAL_FORGE_LUCKPERMS_CLIENT_PASS`，证据目录为 `build/permission-client-d521ac97ca82434699d97f77e9d95023/`。

客户端为实际 Minecraft 1.7.10 / Forge 10.13.4.1614 / Java 8 游戏进程。脚本从本机 Prism 已缓存的版本元数据解析并校验可用校验值的依赖，合并 Forge 的依赖覆盖，用独立游戏目录直接启动；不读取账号文件，不加载原实例的模组或世界。代理与 Uranium 也均为本轮独立进程及数据目录，仅监听本机。Uranium JAR SHA-256 为 `B16747D08BAD4B7C67DB8F1F41C9C31066FEAAAC22B527775F604BDFA885BAD2`。

- 服务端 `fml-server-latest.log` 与客户端 `fml-client-latest.log` 都记录了 modded connection established，服务端记录 PrismSmoke 登录。
- 以实际在线连接的 PlayerIdentity 验证 UNDEFINED、ALLOW、DENY；受限业务命令的执行计数为 0 → 1 → 1。
- 作为该玩家调用 `/lp info`、权限列表，共收到 19 个带样式的组件；通过正式 `Players.sendMessage` 发往客户端。
- 客户端 `[CHAT]` 日志包含 `PERMISSION_CLIENT_COMMAND_PASS`、`PERMISSION_CLIENT_RICH_TEXT_PASS`；没有聊天组件解析错误或残留 `luckperms.command.*` 翻译键。
- 相同连接、相同后端保持 10 秒，验收期间没有断线；随后脚本清理本轮进程及临时游戏目录，保留日志。

这次客户端验收发现并修复了适配器发送 LuckPerms 组件前未调用 `TranslationManager.render` 的问题；翻译必须在 LuckPerms 私有 Adventure 类加载器内完成，再通过 JSON 转入宿主。`PermissionAcceptance` 新增命令文案断言。修复后重新运行原生 H2 验收、MySQL/Redis 故障恢复、Web Editor 和客户端测试，本文上方的目录指向修复后的结果。

测试命令通过代理 API 使用真实在线玩家来源发起，并非键盘输入。验证了真实客户端接收、解析聊天和连接存活；没有宣称人工视觉检查、鼠标点击或悬停交互已验收。脚本遵守 LuckPerms 原生 500ms 玩家命令限流，在两条管理命令之间等待 600ms，没有关闭生产限流。

PostgreSQL、MariaDB、Redis Cluster/Sentinel 及 TLS 部署没有在本次测试矩阵中。构建和本地验收不代表 Maven 已发布；远端结果以 PR/CI 为准。

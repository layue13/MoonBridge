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

玩家探针使用正式 API 的连接身份和快照，没有在这次验收中启动真实 Minecraft 客户端。H2 持久化经过验证；共享 MySQL/PostgreSQL、Redis 等同步方式使用上游实现，但未进行外部服务联调。Web Editor 的网络提交没有进行实测。构建和本地验收不代表 Maven 已发布；远端结果以 PR/CI 为准。

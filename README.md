# MoonBridge

面向 Minecraft 1.7.10 Forge 的玩家会话代理：登录认证、协议转发、跨后端转服，以及供插件使用的玩家、服务器目录、富文本、命令补全、类型化事件和 Channel 消息服务。后端插件共享认证连接，通过 channel 与 proxy 或其他 backend 通信。空岛分配、实例唤醒和 Ban 数据存储由插件实现。

## 文档

- [文档索引](docs/README.md)：按运维、插件开发和架构主题浏览。
- [运行配置](docs/operations.md)：认证模式、监听范围、入口后端和部署约束。
- [WebSocket 接入](docs/websocket.md)：与 TCP 并存的 WebSocket 监听，经 CDN 接入，配套 1.7.10 客户端 mod（[`client-mod/`](client-mod/README.md)）。
- [初次登录路由](docs/initial-routing.md)：候选顺序、插件接管、期限和安全重试边界。
- [插件开发](docs/plugins.md)：Proxy API、事件、命令、转服和初始选服。
- [玩家权限与 LuckPerms](docs/permissions.md)：统一权限接口、命令鉴权和原生平台插件。
- [后端 Bukkit 插件接入](docs/backend-channel-design.md#后端插件接入)：共享宿主、API 依赖与 Channel 示例。
- [发布与依赖版本](docs/publishing.md)、[架构](docs/architecture.md)与[验证方法](docs/testing.md)。

## 快速开始

需要 JDK 25。发行包不包含 Minecraft 后端；要完成玩家登录，需要一个 Minecraft 1.7.10 后端，且 `initialRouting.servers` 中至少一个名称能解析到它（随附配置为 `lobby` → `127.0.0.1:25565`）。

```sh
./gradlew check :proxy-core:installDist                # Windows: .\gradlew.bat check :proxy-core:installDist
cd proxy/core/build/install/moonbridge
java -jar moonbridge.jar --validate-config config/moonbridge.yml
java -jar moonbridge.jar                                 # 默认读取 config/moonbridge.yml
```

需要全部部署物（服务器包、后端插件、客户端 mod）时运行 `./gradlew release`，说明见[发行物与安装位置](docs/release-layout.md)。LuckPerms 平台插件从 `proxy/vendor/luckperms` 子模块编译，默认不构建；需要时先 `git submodule update --init --recursive`，再加 `-Pluckperms`。

默认配置为 `OFFLINE`，仅监听 `127.0.0.1:25577`。公开部署使用 `ONLINE_BUNGEE` 并限制玩家直连后端；认证、监听、入口候选和后端地址见[运行配置](docs/operations.md)。

## 后端插件开发

服务器安装 `moonbridge-backend` JAR（插件名 `MoonBridgeBackend`）。业务插件对同一个构件 `uk.potatolab.moonbridge:backend` 使用 `compileOnly` / `provided`，并通过 `BukkitMessagingService` 获取共享消息服务；无需创建网络连接。源码当前的 `0.1.0-SNAPSHOT` 仅是本地开发构件版本；发布依赖应使用发布工作流提供的不可变版本。API 与完整示例见[后端插件接入](docs/backend-channel-design.md#后端插件接入)。

从 StrataProxy 升级见[迁移说明](docs/operations.md#从-strataproxy-迁移)；历史验收记录保留当时的名称与构件路径。

## 验证状态

当前可引用的验收记录及其覆盖范围见[文档索引](docs/README.md#当前可引用的验证记录)和[验证方法](docs/testing.md)。各记录只证明其注明的提交和环境，不代表生产容量。已知 Uranium 候选服登录停滞见[记录](testing/smoke/results/2026-09-26-uranium-login-stalls.md)及 [Uranium #585](https://git.nest.potatolab.uk:8443/TDLM/Uranium/issues/585)。

生产权限存储统一使用 MariaDB 13.0.2；见 [MariaDB 权限数据库](docs/mariadb.md)。

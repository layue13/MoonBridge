# MoonBridge

面向 Minecraft 1.7.10 Forge 的玩家会话代理：登录认证、协议转发、跨后端转服，以及供插件使用的玩家、服务器目录、富文本、命令补全、类型化事件和 Channel 消息服务。后端插件共享认证连接，通过 channel 与 proxy 或其他 backend 通信。空岛分配、实例唤醒和 Ban 数据存储由插件实现。

## 文档

- [文档索引](docs/README.md)：按运维、插件开发和架构主题浏览。
- [运行配置](docs/operations.md)：认证模式、监听范围、入口后端和部署约束。
- [初次登录路由](docs/initial-routing.md)：候选顺序、插件接管、期限和安全重试边界。
- [插件开发](docs/plugins.md)：Proxy API、事件、命令、转服和初始选服。
- [玩家权限与 LuckPerms](docs/permissions.md)：统一权限接口、命令鉴权和原生平台插件。
- [后端 Bukkit 插件接入](docs/backend-channel-design.md#后端插件接入)：共享宿主、API 依赖与 Channel 示例。
- [发布与依赖版本](docs/publishing.md)、[架构](docs/architecture.md)与[验证方法](docs/testing.md)。

## 本机运行

需要 JDK 25。发行包不包含 Minecraft 后端；要完成玩家登录，需启动一个 Minecraft 1.7.10 后端，并让 `initialRouting.servers` 中至少一个名称能解析到它。

```powershell
git submodule update --init --recursive
.\gradlew.bat check :proxy-core:installDist
.\proxy-core\build\install\moonbridge\bin\moonbridge.bat --config .\proxy-core\build\install\moonbridge\config\moonbridge.yml
```

默认配置为 `OFFLINE`，仅监听 `127.0.0.1:25577`，后端为 `127.0.0.1:25565`。公开部署使用 `ONLINE_BUNGEE`，并限制玩家直连后端。详见[运行配置](docs/operations.md)。

随附配置的入口候选为 `lobby`，地址为 `127.0.0.1:25565`。如果后端名称或地址不同，请编辑发行包 `config/moonbridge.yml` 中的 `backends` 和 `initialRouting.servers`，并确保候选列表按想要的顺序列出明确入口。空候选列表在没有选服插件时会拒绝登录。

若运行已解压的发行包，从发行包目录启动：

```powershell
.\bin\moonbridge.bat --validate-config .\config\moonbridge.yml
.\bin\moonbridge.bat --config .\config\moonbridge.yml
```

在 Linux/macOS 上将 `moonbridge.bat` 换成 `moonbridge`。发行包根目录包含 README、`docs/` 和按记录日期归档的 `smoke/results/`、`benchmarks/results/`；历史记录只证明各自写明的提交和环境。

## 后端插件开发

服务器安装 `moonbridge-backend-bukkit` JAR（插件名 `MoonBridgeBackend`）。业务插件对 `backend-bukkit-api` 使用 `compileOnly` / `provided`，并通过 `BukkitMessagingService` 获取共享消息服务；无需依赖宿主实现或创建网络连接。源码当前的 `0.1.0-SNAPSHOT` 仅是本地开发构件版本；发布依赖应使用发布工作流提供的不可变版本。API 与完整示例见[后端插件接入](docs/backend-channel-design.md#后端插件接入)。

本次改名统一使用 `dev.moonbridge` 包名、`moonbridge` 启动命令和 `moonbridge.yml` 配置文件。现有插件需要重新编译，Bukkit 插件依赖声明和宿主配置目录改用 `MoonBridgeBackend`。历史验收记录保留当时的名称与构件路径。

## 验证状态

`MoonBridgeBackend` 后端宿主与独立 API 已通过两个真实 Uranium / Java 8 服务端的注册、通信、主线程回调、插件停用、重连及注销/租约验收，见[改名与独立 API 验收记录](smoke/results/2026-09-27-moonbridge-api.md)。这是无玩家、隔离服务端上的功能验收，不代表生产容量测试。

最新初次路由专项记录包含自动化测试，以及通过实际 `ProxyMain` 连接真实 Java 8 Uranium 后端的协议探针结果；未进行本分支的完整模组包玩家视觉验收，见[路由验收记录](smoke/results/2026-09-27-initial-routing.md)。2026-09-26 Prism 记录是当日代码和环境下的历史真实客户端及配对测量，不应视作当前路由改动的完整模组包证明，见[历史验收记录](benchmarks/results/2026-09-26-prism-pack.md)。生产容量仍需按部署环境测量。已知 Uranium 候选服登录停滞的调查见[记录](smoke/results/2026-09-26-uranium-login-stalls.md)及 [Uranium #585](https://git.nest.potatolab.uk:8443/TDLM/Uranium/issues/585)。

# MoonBridge

面向 Minecraft 1.7.10 Forge 的玩家会话代理：登录认证、协议转发、跨后端转服，以及供插件使用的玩家、服务器目录、富文本、命令补全、类型化事件和 Channel 消息服务。后端插件共享认证连接，通过 channel 与 proxy 或其他 backend 通信。空岛分配、实例唤醒和 Ban 数据存储由插件实现。

## 文档

- [设计文档索引](docs/README.md)
- [架构与边界](docs/architecture.md)：会话、协议、线程模型、转服、后端目录。
- [插件开发](docs/plugins.md)：生命周期、ServerView、选服、Command 和统一类型化事件 API。
- 首次登录使用显式有序入口服务器配置；空列表会拒绝登录，插件策略可完全接管选服。详见[初始选服契约](docs/plugins.md#初始选服)。
- [运行配置](docs/operations.md)与[后端控制通道](docs/backend-channel-design.md)。
- [消息服务设计契约](docs/channel-messaging-v2.md)：消息身份、路由、插件生命周期及失败语义。
- [测试与性能验证](docs/testing.md)：构建、烟测、真实客户端记录和基准方法。

## 本机运行

需要 JDK 25。

```powershell
.\gradlew.bat check :proxy-core:installDist
.\proxy-core\build\install\moonbridge\bin\moonbridge.bat --config .\proxy-core\build\install\moonbridge\config\moonbridge.yml
```

默认配置为 `OFFLINE`，仅监听 `127.0.0.1:25577`，后端为 `127.0.0.1:25565`。公开部署使用 `ONLINE_BUNGEE`，并限制玩家直连后端。详见[运行配置](docs/operations.md)。

## 后端插件开发

服务器安装 `moonbridge-backend-bukkit` JAR（插件名 `MoonBridgeBackend`）。业务插件以 `compileOnly` / `provided` 依赖 `uk.potatolab:backend-bukkit-api:0.1.0-SNAPSHOT`，通过 `BukkitMessagingService` 获取共享消息服务；无需依赖宿主实现或创建网络连接。API 和完整接入示例见[后端插件接入](docs/backend-channel-design.md#后端插件接入)。

本次改名统一使用 `dev.moonbridge` 包名、`moonbridge` 启动命令和 `moonbridge.yml` 配置文件。现有插件需要重新编译，Bukkit 插件依赖声明和宿主配置目录改用 `MoonBridgeBackend`。历史验收记录保留当时的名称与构件路径。

## 验证状态

`MoonBridgeBackend` 后端宿主与独立 API 已通过两个真实 Uranium / Java 8 服务端的注册、通信、主线程回调、插件停用、重连及注销/租约验收，见[改名与独立 API 验收记录](smoke/results/2026-09-27-moonbridge-api.md)。这是无玩家、隔离服务端上的功能验收，不代表生产容量测试。

已完成当前 Prism 整合包的真实 Mojang 认证、连续 20 次转服、历史 DNS/Agent 发行包烟测及同条件流量对照，见[验收记录](benchmarks/results/2026-09-26-prism-pack.md)。验收覆盖单玩家与本机后端；生产容量需按部署环境测量。已知 Uranium 候选服登录停滞的调查见[记录](smoke/results/2026-09-26-uranium-login-stalls.md)及 [Uranium #585](https://git.nest.potatolab.uk:8443/TDLM/Uranium/issues/585)。

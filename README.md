# StrataProxy

面向 Minecraft 1.7.10 Forge 的玩家会话代理：登录认证、协议转发、跨后端转服，以及供插件使用的玩家、服务器目录、富文本、命令补全、类型化事件和 Channel 消息服务。后端插件共享认证连接，通过 channel 与 proxy 或其他 backend 通信。空岛分配、实例唤醒和 Ban 数据存储由插件实现。

## 文档

- [设计文档索引](docs/README.md)
- [架构与边界](docs/architecture.md)：会话、协议、线程模型、转服、后端目录。
- [插件开发](docs/plugins.md)：生命周期、ServerView、选服、Command 和统一类型化事件 API。
- [运行配置](docs/operations.md)与[后端控制通道](docs/backend-channel-design.md)。
- [消息服务设计契约](docs/channel-messaging-v2.md)：消息身份、路由、插件生命周期及失败语义。
- [测试与性能验证](docs/testing.md)：构建、烟测、真实客户端记录和基准方法。

## 本机运行

需要 JDK 25。

```powershell
.\gradlew.bat check :proxy-core:installDist
.\proxy-core\build\install\strataproxy\bin\strataproxy.bat --config .\proxy-core\build\install\strataproxy\config\strataproxy.yml
```

默认配置为 `OFFLINE`，仅监听 `127.0.0.1:25577`，后端为 `127.0.0.1:25565`。公开部署使用 `ONLINE_BUNGEE`，并限制玩家直连后端。详见[运行配置](docs/operations.md)。

## 验证状态

`StrataProxyBackend` 后端宿主已通过两个真实 Uranium / Java 8 服务端的注册、通信、主线程回调、插件停用、重连及注销/租约验收，见[后端宿主验收记录](smoke/results/2026-09-27-backend-host.md)。这是无玩家、隔离服务端上的功能验收，不代表生产容量测试。

已完成当前 Prism 整合包的真实 Mojang 认证、连续 20 次转服、历史 DNS/Agent 发行包烟测及同条件流量对照，见[验收记录](benchmarks/results/2026-09-26-prism-pack.md)。验收覆盖单玩家与本机后端；生产容量需按部署环境测量。已知 Uranium 候选服登录停滞的调查见[记录](smoke/results/2026-09-26-uranium-login-stalls.md)及 [Uranium #585](https://git.nest.potatolab.uk:8443/TDLM/Uranium/issues/585)。

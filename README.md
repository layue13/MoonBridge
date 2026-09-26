# StrataProxy

面向 Minecraft 1.7.10 Forge 的玩家会话代理：登录认证、协议转发、跨后端转服，以及供插件使用的玩家、服务器目录、命令和连接访问检查 API。空岛分配、实例唤醒和 Ban 数据存储由插件实现。

## 文档

- [设计文档索引](docs/README.md)
- [架构与边界](docs/architecture.md)：会话、协议、线程模型、转服、后端目录。
- [插件开发](docs/plugins.md)：生命周期、ServerView、选服、Command、玩家/IP 访问检查。
- [运行配置](docs/operations.md)与[发现插件](docs/discovery.md)。
- [测试与性能验证](docs/testing.md)：构建、烟测、真实客户端记录和基准方法。

## 本机运行

需要 JDK 25。

```powershell
.\gradlew.bat check :proxy-core:installDist
.\proxy-core\build\install\strataproxy\bin\strataproxy.bat --config .\proxy-core\build\install\strataproxy\config\strataproxy.yml
```

默认配置为 `OFFLINE`，仅监听 `127.0.0.1:25577`，后端为 `127.0.0.1:25565`。公开部署使用 `ONLINE_BUNGEE`，并限制玩家直连后端。详见[运行配置](docs/operations.md)。

## 验证状态

已有合成 TCP 登录/转服测试，以及最小 Uranium + Prism Forge 客户端联机记录。目标整合包、真实 Mojang 认证和生产容量尚未验收；当前不能宣称生产可用。已知 Uranium 候选服登录停滞的调查见[记录](smoke/results/2026-09-26-uranium-login-stalls.md)及 [Uranium #585](https://git.nest.potatolab.uk:8443/TDLM/Uranium/issues/585)。

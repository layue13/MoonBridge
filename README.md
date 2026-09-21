# StrataProxy

StrataProxy 是 Java 25 Minecraft 后端代理。它负责连接接入、后端路由、健康检查、认证/转发协议、Minecraft zlib 压缩和必要的 Forge/Bungee 兼容；运行时后端可由代理插件或受限的 Bukkit 后端适配器注册。

没有 Admin HTTP API、管理 CLI、数据包分析/抓取或 Prometheus/Grafana 资源。Bukkit 适配器仅拥有后端注册、心跳与下线权限，不是动态运维控制面。

```powershell
.\gradlew.bat --no-daemon check :proxy-core:installDist
.\proxy-core\build\install\strataproxy\bin\strataproxy.bat --validate-config .\proxy-core\build\install\strataproxy\config\strataproxy.yml
```

工程只保留两个模块：`proxy-core` 是完整的代理运行时（启动、配置、插件加载与网络转发），`proxy-plugin-api` 是唯一稳定的第三方插件契约。

中文文档从 [docs/README.md](docs/README.md) 开始。

# StrataProxy

StrataProxy 是 Java 25 Minecraft 后端代理。它只做连接接入、静态后端路由、健康检查、认证/转发协议、Minecraft zlib 压缩和必要的 Forge/Bungee 兼容。

没有 Admin HTTP API、管理 CLI、数据包分析/抓取、Prometheus/Grafana 资源或动态运维控制面。

```powershell
.\gradlew.bat --no-daemon check :proxy-app:installDist
.\proxy-app\build\install\strataproxy\bin\strataproxy.bat --validate-config .\proxy-app\build\install\strataproxy\config\strataproxy.yml
```

第三方稳定边界只有 `proxy-plugin-api`。运行时由 `proxy-core`、`proxy-plugin-api`、`proxy-plugin` 与 `proxy-app` 组成。

中文文档从 [docs/README.md](docs/README.md) 开始。

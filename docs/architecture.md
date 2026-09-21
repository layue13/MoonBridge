# 架构与模块边界

```text
第三方插件 ──> proxy-plugin-api
                     │
                 proxy-core
```

| 工程 | 职责 |
| --- | --- |
| `proxy-core` | 完整代理运行时：启动、YAML 配置、插件加载、Minecraft 协议、Netty 转发、路由、注册表、健康检查与兼容处理 |
| `proxy-plugin-api` | 唯一稳定的第三方插件契约 |

模块边界只按稳定契约划分，不按内部实现策略拆分。没有 `proxy-admin-cli` 或 HTTP 管理面；运行时后端只由已加载插件通过公开 API 管理。

`integrations/bukkit-backend-agent/` 是独立的 Java 8 Bukkit 适配器工程，不属于代理运行时模块。它只能通过受限的 `backendAgent` 端点注册、续租和下线自己的后端，不能作为管理 API 使用。

# 架构与模块边界

```text
第三方插件 ──> proxy-plugin-api
                     │
proxy-app ──> proxy-plugin ──> proxy-core
```

| 工程 | 职责 |
| --- | --- |
| `proxy-core` | Minecraft 协议、Netty 转发、路由、注册表、健康检查与兼容处理 |
| `proxy-plugin-api` | 唯一稳定的第三方插件契约 |
| `proxy-plugin` | 插件装载、生命周期与服务实现 |
| `proxy-app` | YAML 配置、进程启动、静态后端与本地注册表持久化 |

模块边界按独立部署与稳定契约划分，不按每一种内部实现策略拆分。没有 `proxy-admin-cli`；运行中后端不通过 HTTP 修改，配置变更后重启代理生效。

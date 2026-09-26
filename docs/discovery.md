# 后端发现插件

使用 DNS 插件时，把示例配置中的主机名换成自己的 DNS 主机名；相对插件目录随配置文件位置一起解析。A 和 AAAA 记录分别刷新：一个地址族查询超时会暂时保留其上次结果，连续三次失败后只撤销该地址族；明确返回无记录会清除该地址族的缓存。两族均无记录时，目录在连续三次查询失败后撤销旧注册。

### Agent 动态注册插件

Agent 插件同样由 `plugins.enabled` 显式启用。它通过一个独立 HTTP 端点接收实例注册和续租，再调用通用 `Servers` API；代理核心不包含 Agent 协议或云平台发现逻辑。端点默认只绑定 `127.0.0.1:28080`，请求使用至少 32 字节的共享密钥做 HMAC-SHA256 签名，带 60 秒时间窗和一次性随机 nonce。请求体、并发请求数、实例数和租约时长都有上限。每个 Agent 进程使用新的 UUID generation；活动租约不能被其他 generation 覆盖。注销或过期的 generation 会保留到最大租约时长加 60 秒签名时间窗之后，再由周期清理器删除；这能拦截在租约切换期间延迟到达的旧请求，同时避免 Agent 重启次数累积导致注册耗尽。异常退出后，租约到期会移除后端。

```yaml
plugins:
  directory: ../plugins
  enabled:
    dev.strataproxy.plugins.agent.AgentDiscoveryPlugin:
      secret: "replace-with-a-private-random-secret-of-32-bytes-or-more"
      # host: 127.0.0.1
      # port: "28080"
      # concurrency: "4"
      # maxBodyBytes: "4096"
      # maxLeaseSeconds: "60"
      # maxInstances: "128"
```

密钥不要提交到仓库；插件和 Agent 必须使用同一个值。跨主机部署时，应绑定到私有网络，并通过可信 TLS 终止器或私有链路保护传输；HMAC 验证身份和请求完整性，但不加密流量。`AgentRegistrationClient` 是仅依赖 JDK 的示例客户端：设置 `STRATAPROXY_AGENT_SECRET`，然后运行 `AgentExampleMain <http-endpoint> <agent-id> <backend-name> <tcp-address>`，其中端点形如 `http://127.0.0.1:28080/registration`。示例每 10 秒续租一次，租约 30 秒；网络故障和服务端暂时不可用时继续重试，租约过期导致旧 generation 被拒绝时换用新 generation 注册；认证或请求格式错误则退出。正常退出时尝试注销，异常退出由租约过期清理。Agent 协议只注册后端名称与地址，不需要消息队列。

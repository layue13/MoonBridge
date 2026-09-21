# 配置说明

配置文件是 YAML。未知字段会被忽略，因此升级后应删除已经不再支持的 `admin`、`packetAnalysis` 和旧的 `observability` 子字段。

```yaml
network:
  bind: "0.0.0.0:25577"
  maxFrameBytes: "8mb"
  maxConnections: 10000

registry:
  staticServers: true
  persistenceEnabled: true
  persistencePath: "data/registry.json"
  healthCheckEnabled: true
  healthCheckMode: "tcp"

compression:
  mode: adaptive
  minThreshold: 256
  maxThreshold: 8192

observability:
  flushIntervalSeconds: 5

servers:
  - name: "lobby-1"
    address: "127.0.0.1:25565"
    tags: ["lobby"]
    protocolRange: "any"
    weight: 100
```

`network` 控制监听、帧上限和连接限制；`registry` 控制静态后端、健康检查和本地持久化；`compression` 控制 Minecraft 压缩协商与重写；`observability.flushIntervalSeconds` 仅控制后端已观测负载写回间隔。

`status` 控制服务器列表响应，`auth` 控制 online-mode 登录校验，`forwarding` 可选 `none`、`velocity-modern`、`bungee-legacy` 或 `bungee-guard`，`native` 控制 Netty 原生传输偏好。完整默认值见 [strataproxy.yml](../proxy-app/src/main/resources/config/strataproxy.yml)。

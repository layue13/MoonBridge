# 运维说明

生产运行只需要代理进程、一个 YAML 配置文件和已声明的后端。后端增删、权重或排空状态通过配置变更和重启完成。

```powershell
.\strataproxy.bat --config .\strataproxy.yml --validate-config
.\strataproxy.bat --config .\strataproxy.yml
```

上线前确认监听端口可达、每个后端的 TCP 或 Minecraft status 健康检查正常，并根据需要启用 online-mode 和受保护的转发密钥。

本项目不提供 Admin HTTP API、Prometheus 指标端点、数据包抓取或运行时后端管理。

容器和 systemd 示例见 [deployment](../deployment/README.md)。

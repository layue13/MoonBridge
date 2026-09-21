# 部署

将 `proxy-core` 的 `installDist` 产物和 YAML 配置部署到目标主机即可。示例 systemd 与容器文件只暴露 Minecraft 监听端口 `25577`，注册表持久化路径通过 `STRATAPROXY_DATA_DIR` 控制。

```bash
./gradlew --no-daemon :proxy-core:installDist
```

启动前执行 `strataproxy --validate-config /path/to/strataproxy.yml`。生产环境不包含管理端口、指标端点或数据包抓取功能。

# 命令速查

这页按运维任务组织命令。先构建 CLI：

```powershell
.\gradlew.bat --no-daemon :proxy-admin-cli:installDist :proxy-query:installDist
```

为了少打字，可以先定义变量：

```powershell
$admin='.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat'
$query='.\proxy-query\build\install\strataproxy-query\bin\strataproxy-query.bat'
```

如果 Admin API 配了 bearer token，每条 Admin CLI 命令加：

```powershell
--token "$env:STRATAPROXY_ADMIN_TOKEN"
```

## 健康与总览

```powershell
& $admin --base-url http://127.0.0.1:8080 health
& $admin --base-url http://127.0.0.1:8080 ready
& $admin --base-url http://127.0.0.1:8080 overview
& $admin --base-url http://127.0.0.1:8080 diagnostics
```

`health` 看进程是否活着；`ready` 看是否有后端能接玩家。

自动化 SLO 检查：

```powershell
& $admin --base-url http://127.0.0.1:8080 slo --require-ready --max-event-loop-delay-ms 5 --max-rejected 0 --max-anomalies 0
```

## 后端服务器

列出后端：

```powershell
& $admin --base-url http://127.0.0.1:8080 servers list
```

查看单个后端：

```powershell
& $admin --base-url http://127.0.0.1:8080 servers get survival-1
```

注册或替换后端：

```powershell
& $admin --base-url http://127.0.0.1:8080 servers register `
  --name survival-1 `
  --address 10.0.0.12:25565 `
  --tag survival,forge `
  --capability large-payload,modern-forwarding `
  --protocol-range any `
  --weight 100 `
  --soft-capacity 180 `
  --hard-capacity 220 `
  --metadata host=survival.example.net,group=survival
```

调整灰度权重或 metadata：

```powershell
& $admin --base-url http://127.0.0.1:8080 servers update survival-1 --weight 20 --metadata group=survival-canary
```

维护 drain：

```powershell
& $admin --base-url http://127.0.0.1:8080 servers drain survival-1
& $admin --base-url http://127.0.0.1:8080 servers undrain survival-1
```

删除后端：

```powershell
& $admin --base-url http://127.0.0.1:8080 servers remove survival-1
```

## 路由

不接入真实玩家，预览一次路由决策：

```powershell
& $admin --base-url http://127.0.0.1:8080 routes preview `
  --route survival.example.net `
  --protocol-version 763 `
  --remote-address 127.0.0.1:50000 `
  --tag survival `
  --capability large-payload
```

玩家被拒绝或被送到错误后端时，先跑这个命令。

## 指标与诊断

```powershell
& $admin --base-url http://127.0.0.1:8080 metrics
& $admin --base-url http://127.0.0.1:8080 native
& $admin --base-url http://127.0.0.1:8080 compression
& $admin --base-url http://127.0.0.1:8080 packets
& $admin --base-url http://127.0.0.1:8080 mod-payloads --samples
& $admin --base-url http://127.0.0.1:8080 players
& $admin --base-url http://127.0.0.1:8080 anomalies --samples
& $admin --base-url http://127.0.0.1:8080 backpressure
```

把在线玩家转到另一个已注册后端：

```powershell
& $admin --base-url http://127.0.0.1:8080 players transfer Steve survival-2
```

转服会先连接目标后端，重放玩家的后端登录帧，吞掉目标后端的 Login Success，然后替换 relay 并关闭旧后端连接。

## Payload Capture

Capture 是显式开启、有数量和字节上限、会自动过期的诊断功能。

```powershell
& $admin --base-url http://127.0.0.1:8080 captures start `
  --id survival-capture `
  --server survival-1 `
  --direction frontend_to_backend `
  --max-samples 64 `
  --max-bytes 256 `
  --duration-ms 30000

& $admin --base-url http://127.0.0.1:8080 captures get survival-capture
& $admin --base-url http://127.0.0.1:8080 captures stop survival-capture
```

capture 数据按敏感数据处理。

## Zstd Samples

采集：

```powershell
& $admin --base-url http://127.0.0.1:8080 zstd-samples start `
  --id registry-train `
  --server survival-1 `
  --direction backend_to_frontend `
  --max-samples 5000 `
  --max-bytes 32768 `
  --duration-ms 300000
```

导出：

```powershell
& $admin --base-url http://127.0.0.1:8080 zstd-samples export registry-train --out .\samples\registry
```

训练：

```powershell
& $admin zstd-samples train --in .\samples\registry --out .\data\zstd\registry.zdict --max-dict 16384 --level 1
```

停止：

```powershell
& $admin --base-url http://127.0.0.1:8080 zstd-samples stop registry-train
```

上线给用户前先看 [Zstd 压缩调参指南](compression-zstd.md)。

## Query 与压测

Status ping：

```powershell
& $query --host 127.0.0.1 --port 25577 status --virtual-host play.example.net
```

空闲连接探测：

```powershell
& $query --host 127.0.0.1 --port 25577 idle-load --connections 1000 --hold-ms 10000 --parallelism 256 --min-connected 1000 --min-alive 1000 --max-failed 0
```

握手路由探测：

```powershell
& $query --host 127.0.0.1 --port 25577 handshake-load --connections 2000 --virtual-host play.example.net --parallelism 256 --min-handshaken 2000 --min-alive 2000 --max-failed 0
```

流量转发探测：

```powershell
& $query --host 127.0.0.1 --port 25577 traffic-load --connections 2000 --virtual-host play.example.net --login-start --player-template load%05d --packets-per-connection 200 --payload-bytes 64 --parallelism 256 --min-handshaken 2000 --min-packets-sent 400000 --max-failed 0
```

用于 backpressure 测试的慢后端：

```powershell
& $query slow-sink --bind-host 127.0.0.1 --port 25565 --duration-ms 30000 --read-chunk-bytes 1 --read-delay-ms 100
```

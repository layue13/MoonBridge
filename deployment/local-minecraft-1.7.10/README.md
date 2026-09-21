# 本地 Minecraft 1.7.10 后端池

此目录提供用于测试 StrataProxy 的本地三后端 Minecraft 1.7.10 环境脚本和配置。

## 后端

| 名称 | 地址 | 路由主机 | 标签 |
| --- | --- | --- | --- |
| lobby-1 | 127.0.0.1:25565 | lobby.local | lobby, mc-1.7.10 |
| survival-1 | 127.0.0.1:25566 | survival.local | survival, mc-1.7.10 |
| minigame-1 | 127.0.0.1:25567 | minigame.local | minigame, mc-1.7.10 |

脚本会按需从 Mojang 版本元数据下载服务端 jar 到 `bin/minecraft_server.1.7.10.jar`，并校验 SHA-1：

```text
952438ac4e01b4d115c5fc38f891710c4941df29
```

## 启动

先阅读并接受 Minecraft EULA；确认接受后运行：

```powershell
.\deployment\local-minecraft-1.7.10\start-backends.ps1 -AcceptEula
```

使用本地 1.7.10 配置启动 StrataProxy：

```powershell
.\deployment\local-minecraft-1.7.10\start-proxy.ps1
```

Minecraft 1.7.10 客户端连接：

```text
127.0.0.1:25577
```

## 检查与停止

```powershell
.\deployment\local-minecraft-1.7.10\status.ps1
.\deployment\local-minecraft-1.7.10\stop-backends.ps1
```

各后端的标准输出和错误日志写在自己的后端目录中。Git 有意忽略生成的运行时文件：`bin/`、`backend-*/`、`*.pid` 和 `*.log`。

# 快速开始

这份文档只解决一件事：让一个 StrataProxy 跑在一个 Minecraft 后端前面。第一次跑通后，再看配置说明和运维手册。

## 1. 准备

- JDK 25。
- 一个 Minecraft 后端，例如 `127.0.0.1:25565`。
- 一个给玩家连接的代理端口，默认 `25577`。
- 下面示例使用 Windows PowerShell；Linux 用 `./gradlew` 和 `bin/` 下的 shell 脚本。

如果系统默认 Java 不是 25，先设置：

```powershell
$env:JAVA_HOME='C:\Program Files\Zulu\zulu-25'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
java -version
```

## 2. 构建

```powershell
.\gradlew.bat --no-daemon :proxy-app:installDist :proxy-admin-cli:installDist :proxy-query:installDist
```

代理程序在：

```text
proxy-app\build\install\strataproxy\
```

## 3. 创建配置

复制默认配置：

```powershell
Copy-Item .\proxy-app\src\main\resources\config\strataproxy.yml .\strataproxy.yml
```

第一次运行只需要改这些字段：

```yaml
network:
  bind: "0.0.0.0:25577"

admin:
  enabled: true
  bind: "127.0.0.1:8080"
  bearerToken: ""

servers:
  - name: "lobby-1"
    address: "127.0.0.1:25565"
    tags: ["lobby"]
    capabilities: ["modern-forwarding"]
    protocolRange: "any"
    weight: 100
    softCapacity: 500
    hardCapacity: 600
    drainMode: false
    metadata:
      group: "lobby"
      host: "localhost"
```

如果 `bearerToken` 为空，Admin API 必须只绑定 `127.0.0.1`。

## 4. 校验配置

```powershell
.\proxy-app\build\install\strataproxy\bin\strataproxy.bat --validate-config .\strataproxy.yml
```

先修掉所有错误，再启动。

## 5. 启动

```powershell
.\proxy-app\build\install\strataproxy\bin\strataproxy.bat .\strataproxy.yml
```

玩家连接代理端口，而不是直接连后端：

```text
127.0.0.1:25577
```

## 6. 检查运行状态

另开一个终端：

```powershell
$admin='.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat'
& $admin --base-url http://127.0.0.1:8080 health
& $admin --base-url http://127.0.0.1:8080 ready
& $admin --base-url http://127.0.0.1:8080 overview
& $admin --base-url http://127.0.0.1:8080 servers list
```

通过代理做一次 Minecraft status 查询：

```powershell
.\proxy-query\build\install\strataproxy-query\bin\strataproxy-query.bat --host 127.0.0.1 --port 25577 status --virtual-host localhost
```

## 7. 下一步

- 运行时增删后端，看 [命令速查：后端服务器](commands.md#后端服务器)。
- 想知道 YAML 怎么填，看 [配置说明](configuration.md)。
- 上 Linux/systemd/container，看 [部署说明](../../deployment/README.md)。
- 调 Zstd 和 dictionary，看 [Zstd 压缩调参指南](compression-zstd.md)。

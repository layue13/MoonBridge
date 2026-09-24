# StrataProxy

StrataProxy 是面向 Minecraft 1.7.10 Forge 的玩家会话代理。项目正在从零重写；当前分支已有离线和在线模式的初始登录路径，尚未达到生产可用标准。

## 核心边界

- **玩家会话**：代理负责入服、连接后端、转发和断线清理。玩家身份包含 UUID 与连接代次，避免旧连接的异步结果作用到新连接。
- **服务器目录**：静态配置与插件调用同一个注册接口。目录维护地址、声明的容量，以及本代理连接和入服预留的数量；它不采集后端 CPU、TPS、内存等负载。同名重注册生成新句柄，旧异步结果不能修改新条目。
- **插件 API**：插件可查询 `PlayerView`、`ServerView`，动态注册后端，并以一个异步回调决定初始落点。空岛分配、实例唤醒和玩法数据由插件自行实现。
- **发现方式**：DNS 是独立插件，核心不认识 DNS 或 Agent。配置只启用列出的插件；插件关闭时其注册会清理。
- **协议热路径**：协议 5 的初始帧解析与普通数据转发分离。建立会话后使用 Netty `ByteBuf` 原样转发，并按目标通道可写状态控制读取。
- **日志**：SLF4J API 与 Logback 运行时。

## 当前可运行范围

配置必须显式选择 `authentication: OFFLINE` 或 `ONLINE_BUNGEE`。两种模式均解析协议 5 Handshake 与 Login Start，等待异步落点，预留容量，连接后端，确认 Login Success，然后转发普通字节流。在线模式执行加密握手、异步会话校验，并向可信的 Bungee 兼容后端转发已验证身份。状态查询由代理直接回答。DNS 插件使用 A/AAAA 地址解析并调用通用服务器注册 API。

**尚未完成**：安全跨后端转服、Forge 握手完成状态与切服重建、真实 1.7.10 Forge 整合包运行和同条件性能验收。在线认证仅通过注入会话校验器的合成 TCP 测试；真实 Mojang 服务、Uranium/Bungee 兼容后端及整合包尚未联机验证。因此当前实现不能宣称生产可用或 Forge 转服兼容。

## 配置与运行

默认配置：`proxy-core/src/main/resources/config/strataproxy.yml`。安装包包含静态后端示例和 `strataproxy-dns.example.yml`；后者通过 `plugins.enabled` 启用 DNS 插件，`backends: []` 表示不使用静态后端。相对插件目录从启动时的工作目录解析。`ONLINE_BUNGEE` 只能连接已启用旧版 Bungee 身份转发、且限制直连的可信后端。

```powershell
.\gradlew.bat :proxy-core:installDist
.\proxy-core\build\install\strataproxy\bin\strataproxy.bat --validate-config .\proxy-core\build\install\strataproxy\config\strataproxy.yml
.\proxy-core\build\install\strataproxy\bin\strataproxy.bat --config .\proxy-core\build\install\strataproxy\config\strataproxy.yml
```

使用 DNS 插件时，把安装包的 `plugins` 目录和配置文件放在启动工作目录可访问的位置，并在示例配置中换成自己的 DNS 主机名。

## 开发验证

```powershell
.\gradlew.bat check
.\gradlew.bat :proxy-core:installedDistSmokeTest
```

定向测试覆盖目录代次与容量并发、插件生命周期与超时、协议边界、relay 背压以及合成 TCP 登录。真实客户端和服务端整合包尚未提供；合成测试不替代实服验证。

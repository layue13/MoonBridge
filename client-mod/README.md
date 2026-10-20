# MoonBridge WebSocket 客户端模组（1.7.10 Forge）

让 1.7.10 Forge 客户端通过 WebSocket 连接 MoonBridge 代理，从而可以经由 Cloudflare 等 CDN 转发流量。
服务器地址以 `ws://` 或 `wss://` 开头时走 WebSocket；其他任何地址的行为与原版完全相同（普通 TCP）。
服务器列表的 ping 和实际进入服务器都支持。

## 安装

1. Minecraft 1.7.10 + Forge **10.13.4.1614**，Java 8 或更新（GTNH 的 lwjgl3ify 环境也可以）。
2. 把以下两个 jar 放进 `mods/`：
   - 本模组 `moonbridgews-<版本>.jar`（`build/libs/` 下不带 `-dev` / `-sources` 后缀的那个）；
   - [UniMixins](https://github.com/LegacyModdingMC/UniMixins)（建议 0.3.x；构建和测试使用的是 0.3.1）。本模组是 coremod + 早期 Mixin，依赖 UniMixins 提供的 GTNH Mixin 加载器，必须安装。
3. 只需要装在客户端；服务器端不需要，也不会检查。

## 地址格式

在“添加服务器”或“直接连接”里填写：

| 地址 | 含义 |
| --- | --- |
| `wss://mc.example.com/mc` | TLS，443 端口，路径 `/mc` |
| `wss://mc.example.com:8443/mc?token=abc` | 自定义端口，查询串原样发送 |
| `ws://1.2.3.4:8080/mc` | 明文 WebSocket，8080 端口 |
| `ws://[2001:db8::1]:8080/` | IPv6 字面量需要方括号 |
| `mc.example.com:25565` | 不是 ws/wss，原版 TCP（包括 SRV 记录） |

- 默认端口：`ws` 为 80，`wss` 为 443；路径为空时请求 `/`。`#` 之后的片段会被忽略。
- Host 头为 URL 中的主机名（非默认端口时带 `:端口`）；`wss` 的 SNI 和证书校验都使用该主机名，按 JDK 默认信任库做正常的证书与主机名校验，不能跳过。
- 路径只允许可打印 ASCII 且不含空格，其他字符请先做百分号编码。
- 发给后端的 Minecraft 握手包里的地址/端口就是 URL 的主机名和端口。
- 每个 WebSocket 消息都是 BINARY 帧，内容是原版 TCP 字节流的一段（已经过原版的加密和长度前缀），分段边界任意。服务器发来 TEXT 帧视为协议错误并断开。

## 常见问题

- **显示 “Unknown host”**：地址以 `ws://`/`wss://` 开头但格式不合法（日志里有 `Invalid WebSocket server address` 及原因），或 URL 主机名无法解析。
- **“Internal Exception: java.io.IOException: WebSocket handshake ... failed: server answered 'HTTP/1.1 404 ...'”**：路径不对，或 CDN/反向代理没有转发 WebSocket（Cloudflare 需开启 WebSockets，源站需返回 101）。403/1xxx 多为 CDN 规则拦截。
- **“TLS handshake with ... failed”/`PKIX path building failed`**：证书不受信任或主机名不匹配。旧版 Java 8（8u101 之前）缺少 Let's Encrypt 等新根证书，请升级 Java。
- **服务器列表里 ws 条目显示 “Can't connect to server”**：先确认用同一地址能否加入；ws 地址不会再尝试原版的旧版（0xFE）ping。
- **日志**：成功时会打印 `[MoonBridgeWs]: Connecting over WebSocket to ws://...`。没有这行说明模组或 UniMixins 未加载。

## 构建与测试

这是独立的 Gradle 构建（GTNH ExampleMod 模板 + RetroFuturaGradle），不属于根项目的 `settings.gradle.kts`，CI 也不会构建它。

```sh
cd client-mod
bash ./gradlew build          # 编译、反混淆打包、spotless/checkstyle、JUnit 测试
bash ./gradlew test           # 仅测试 dev.moonbridge.clientws.net（不依赖 Minecraft）
bash ./gradlew runObfClient   # 用混淆环境启动客户端；若模组未加载，把 run/obfuscated/mods/ 里的 jar 复制到 run/client/mods/
```

Gradle 守护进程使用 JDK 25（`gradle/gradle-daemon-jvm.properties`，可自动下载），编译工具链由模板自动配置。
构建版本号取自 git 描述。

## 代码结构

- `dev.moonbridge.clientws.net`：与 Minecraft 无关、可单独测试的部分。`WebSocketAddress`（URL 解析）、`WebSocketHandshakeHandler`（RFC 6455 握手，握手完成前缓存出站写入）、`WebSocketFrameAdapter`（帧 ⇄ 原始字节流）、`WebSocketPipeline`（安装到管线最前面）、`WebSocketConnectContext`（把 URL 从地址解析传到建立连接的线程）。
- `dev.moonbridge.clientws.mixins.early`：早期 Mixin，目标为 `ServerAddress.func_78860_a`、`NetworkManager.provideLanClient`（`func_150726_a`）及其匿名 `ChannelInitializer`（`NetworkManager$2`）、`GuiConnecting.func_146367_a`、`OldServerPinger.func_147225_b`。
- 帧编解码使用 Minecraft 自带的 Netty 4.0.10.Final（`WebSocket13FrameEncoder/Decoder`、`SslHandler`），没有打包其他 Netty。

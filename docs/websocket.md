# WebSocket 接入

MoonBridge 可以在 TCP `listen` 之外再开一个 WebSocket 玩家监听。这样玩家流量可以经 CDN（如 Cloudflare）或 HTTP 反向代理转发，从而换一条线路。两种入口同时工作：原版客户端照常连 TCP 端口；装了 [MoonBridge WebSocket 客户端 mod](../client-mod/README.md) 的客户端填 `ws://` 或 `wss://` 地址走 WebSocket。两种连接进入同一套会话、选服、转服和插件逻辑，共用 `maxConnections`。

CDN 能否降低延迟或丢包，取决于玩家到 CDN 边缘、以及边缘到源站这两段线路。本仓库只验证了协议正确性，没有做延迟或容量测量；是否值得启用应在实际线路上测量。

## 协议约定

客户端 mod 与代理按以下约定实现，其他客户端或网关也可以照此接入：

- 标准 RFC 6455 WebSocket。客户端向配置的 `path` 发送 HTTP/1.1 `GET` 升级请求，不需要子协议，也不协商扩展（不压缩）。
- 升级完成后，每条消息都是**二进制**帧，载荷是原始 Minecraft TCP 字节流的一段。这些字节与直连 TCP 时完全相同：带 VarInt 长度前缀，在线模式下还经过 Minecraft 自己的 AES/CFB8 加密。
- 消息边界没有含义。一条消息可以只含半个 Minecraft 帧，也可以含多个帧；接收方必须按字节流拼接。代理对每次写出发送一条消息，单条消息载荷上限为一个 1.7.10 帧的最大值（2 MiB + 3 字节）。
- 收到文本帧视为协议错误，代理直接关闭连接。Ping 由代理回复 Pong；关闭帧关闭连接。代理关闭会话时先发送关闭帧。
- 请求其他路径、非升级请求返回 `404` 并关闭。连接建立后，若在 `handshakeTimeoutSeconds` 内没有完成升级，代理关闭连接；尚未升级的连接同样占用 `maxConnections` 名额。

## 配置

```yaml
listen: "0.0.0.0:25565"          # 原生 TCP，保持不变
authentication: ONLINE_BUNGEE
websocket:
  listen: "127.0.0.1:8080"       # WebSocket 监听；通常只对本机反向代理开放
  path: "/mc"                     # 默认 "/"
  # tls: { certificateChain: "certs/fullchain.pem", privateKey: "certs/privkey.pem" }
  trustedProxies: ["127.0.0.1"]   # 只有这些来源可以提供真实 IP 头
  realIpHeader: "X-Forwarded-For" # 默认值；也可用 CF-Connecting-IP、X-Real-IP
  handshakeTimeoutSeconds: 10     # 1–60，默认 10
```

| 键 | 说明 |
| --- | --- |
| `listen` | 必填，`host:port`，端口须大于 0。`OFFLINE` 模式下与 TCP `listen` 相同，必须是字面量回环 IP，除非设置 `allowOfflinePublicAccess: true`。 |
| `path` | 升级请求的路径，须以 `/` 开头，不含查询串和空白。 |
| `tls` | 可选。配置后监听端直接提供 `wss://`；PEM 证书链与**未加密的 PKCS#8** 私钥，路径相对于配置文件。PKCS#1 私钥（`BEGIN RSA PRIVATE KEY`）可用 `openssl pkcs8 -topk8 -nocrypt -in key.pem -out key.pk8.pem` 转换。不配置时为明文 `ws://`，由 CDN 或反向代理终结 TLS。 |
| `trustedProxies` | IP 或 CIDR 列表。仅当 TCP 对端属于该列表时才读取真实 IP 头；为空时始终使用 TCP 对端地址。 |
| `realIpHeader` | 真实 IP 头名称，大小写不敏感。`X-Forwarded-For` 从右往左取第一个不属于 `trustedProxies` 的地址（全部可信时取最左项）；其他头必须恰好出现一次且是 IP 字面量。可信来源发送了格式错误的头时拒绝连接，不会回退。 |
| `handshakeTimeoutSeconds` | 从建连到升级完成的期限。 |

`--validate-config` 同样会加载并校验 TLS 证书和 `trustedProxies`。启动日志会打印 WebSocket 监听的地址与路径。

### 玩家地址

代理按上表确定的地址就是该玩家的地址，用于 `ConnectionAdmissionEvent`、`PlayerAdmissionEvent`、Mojang `hasJoined` 的 `ip` 参数和 Bungee 旧版转发写给后端的 IP。来源不可信时使用 TCP 对端地址，即 CDN 边缘或反向代理的地址，这时按 IP 封禁会作用于整个边缘节点。**经 CDN 接入时必须正确配置 `trustedProxies`**，同时不能把源站端口暴露给 CDN 以外的来源：任何能直连源站、且来源在 `trustedProxies` 内的人都可以伪造真实 IP 头。

经 WebSocket 接入的连接在升级完成后才触发 `ConnectionAdmissionEvent`，这时已经确定了真实地址。

## 部署示例

### Cloudflare + 本机 nginx

```nginx
server {
    listen 443 ssl;
    server_name mc.example.com;
    # ssl_certificate / ssl_certificate_key：源站证书（Cloudflare Origin CA 或公网证书）

    location /mc {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_buffering off;
        proxy_read_timeout 1h;
        proxy_send_timeout 1h;
    }
}
```

对应的 MoonBridge 配置：`websocket.listen: "127.0.0.1:8080"`、`path: "/mc"`、`realIpHeader: "X-Forwarded-For"`。`trustedProxies` 同时列出 `127.0.0.1` 和 [Cloudflare 公布的 IP 段](https://www.cloudflare.com/ips/)：Cloudflare 把客户端地址追加到 `X-Forwarded-For`，nginx 再追加 Cloudflare 边缘地址，代理从右往左跳过这两类可信地址后得到玩家 IP。nginx 本身也应只接受 Cloudflare IP 段的连接。

Cloudflare 侧：

- DNS 记录开启代理（橙色云朵），在 Network 设置中开启 WebSockets。
- 只有 Cloudflare 支持的 HTTP/HTTPS 端口能经它代理，例如 HTTPS 的 443、2053、2083、2087、2096、8443 和 HTTP 的 80、8080、8880、2052、2082、2086、2095；Minecraft 默认的 25565 不在其中。
- SSL/TLS 模式建议用 Full (strict)，回源也走 TLS。
- Cloudflare 会在边缘维护或升级时断开长连接，玩家会掉线后需要重连；这是 CDN 行为，代理无法保持连接。具体套餐对 WebSocket 时长和流量的限制以 Cloudflare 当前文档和条款为准。

玩家在客户端填 `wss://mc.example.com/mc`。

### 不经 nginx，Cloudflare 直接回源

`websocket.listen` 绑定公网地址，并开启 `tls`（Cloudflare Origin CA 证书即可，Cloudflare 选 Full (strict)）；`trustedProxies` 填 Cloudflare IP 段，`realIpHeader: "CF-Connecting-IP"`。防火墙只放行 Cloudflare IP 段访问该端口。

### 直连 WebSocket（无 CDN）

开发或测试时可以把 `websocket.listen` 设为可直连的地址并留空 `trustedProxies`，客户端填 `ws://host:port/path`。

## 客户端

客户端 mod 的安装、地址格式和排错见 [`client-mod/README.md`](../client-mod/README.md)。服务器列表 ping 和加入服务器都会走 WebSocket；普通 `host:port` 地址行为与原版一致，未安装 mod 的玩家不受影响。

## 验证范围

自动化测试（`WebSocketListenerTest`、`WebSocketFrameAdapterTest`、`ClientAddressPolicyTest`）在真实 socket 上覆盖：

- TCP 与 WebSocket 玩家同时登录同一代理并转发 PLAY 帧；
- 握手跨消息拆分、多帧合并为一条消息、PLAY 帧跨消息拆分且中间夹 Ping；
- 在线模式在 WebSocket 内的加密登录，Mojang 验证 IP 与 Bungee 转发 IP 取自可信头；
- 不可信来源不能伪造地址；
- 错误路径返回 404、文本帧关闭连接；
- 未升级连接占用并归还 `maxConnections` 名额，以及升级期限；
- 由配置加载 PEM 证书后提供 `wss://`。

没有覆盖：真实 CDN 链路、真实客户端游戏内体验、经 WebSocket 的延迟与吞吐。

# 日志策略

本文规定代理写哪些日志、写在哪个级别、哪些内容永远不写。新增日志按本文选级别；运维按本文判断日志能回答什么问题。

## 日志去哪里

- 控制台和 `logs/latest.log` 内容相同。`latest.log` 是当天的日志，前几天的日志压缩为 `logs/yyyy-MM-dd.log.gz`，保留 14 天，总大小上限 2 GB。
- `logs/` 相对启动时的当前目录，即与 `moonbridge.jar` 同级。改到其他位置：`java -Dmoonbridge.logDir=/var/log/moonbridge -jar moonbridge.jar`。
- 默认级别为 INFO。要更换格式、保留天数或输出位置，把 jar 内的 `logback.xml` 复制出来修改，并用 `-Dlogback.configurationFile=logback.xml` 指定。

## 级别的含义

| 级别 | 规则 | 量 |
| --- | --- | --- |
| ERROR | 代理或某项功能整体停止工作 | 几乎没有 |
| WARN | 需要运维处理：插件失败、过载、配置问题、后端拒绝或断开、玩家没能按请求转服 | 少；重复的事件会被合并 |
| INFO | 运维会问到的事件，每件事一行：玩家加入、离开、转服、未能加入；服务器上线、下线；启动和关闭 | 每个事件一行，从不按包 |
| DEBUG | 协议的逐步过程：拨号、刷写、握手、超时、帧处理失败 | 默认关闭 |

## INFO 和 WARN 写什么

玩家事件写到 logger `moonbridge.players`：

```
INFO  Tester (3f2a…e1) connected from 203.0.113.7 via TCP -> lobby
INFO  Tester moved lobby -> survival
WARN  Tester could not move to creative: backend login timed out (stays on survival)
INFO  Tester left survival after 1h 12m: client disconnected
INFO  Steve from 203.0.113.9 could not join: Mojang session verification failed
WARN  Connection limit reached: refused 37 connection(s); raise maxConnections if this is expected load
```

- `left` 的原因来自固定集合：`client disconnected`、`server closed the connection`、`proxy shutting down`、`timed out`、`disconnected by a plugin: <文本>`、`client/server sent an invalid packet`、`client/server connection error`。
- `could not join` 只在客户端发出 Login Start 之后才写；服务器列表 ping 和端口扫描不写。原因是代理发给玩家的那句话（例如 `No entry server could be reached.`、`Login timed out.`、插件的拒绝文本），或认证阶段的固定原因（`Mojang session verification failed`、`could not reach the Mojang session server`、`this account is already connected` 等）。
- 转服成功只在实际切换后写 `moved`；请求的转服没有发生而玩家仍在线时写 WARN，括号里是玩家现在所在的服务器。

服务器事件写到 logger `moonbridge.servers`：

```
INFO  Server lobby registered by static config at 127.0.0.1:25565
INFO  Server survival registered by control channel (island-a-1) at 10.0.0.5:25565
INFO  Server survival moved from 10.0.0.5:25565 to 10.0.0.6:25565 (control channel (island-a-1))
INFO  Control-channel lease for server survival (instance island-a-1) expired without a heartbeat
INFO  Server survival unregistered (control channel (island-a-1))
WARN  Server lobby at 127.0.0.1:25565 is unreachable; players are routed to the next entry server
INFO  Server lobby is reachable again
WARN  Rejected backend registration from 10.0.0.5: backend signature rejected; check backendChannel.clients for this instance
```

- 同一控制通道实例重连时，地址不变就不再写 `registered`。
- `unreachable` 每次故障只写一次，直到某次初次登录拨号成功才写 `reachable again`；不是每个玩家写一次。
- 被拒绝的后端注册按「后端地址 + 原因」去重：配置错误的后端每隔几秒重试，也只写一行，原因变化或注册成功后才会再写。

插件和生命周期：

```
INFO  Starting MoonBridge 0.1.0 on Java 25.0.4+1 with ONLINE_BUNGEE authentication
INFO  Plugin luckperms-moonbridge-0.1.0.jar:dev.moonbridge.luckperms.LuckPermsMoonBridgePlugin enabled
INFO  Listening on /0.0.0.0:25577
INFO  Ready: 3 server(s) in the catalog, 1 plugin(s) enabled, backend control channel on 0.0.0.0:28081
WARN  Overloaded: rejected 12 plugin command request(s); a plugin may be blocking its worker threads
INFO  Shutting down with 42 player(s) online
INFO  Plugin luckperms-moonbridge-0.1.0.jar:dev.moonbridge.luckperms.LuckPermsMoonBridgePlugin disabled
INFO  Stopped
```

- 过载（连接数上限、插件命令、命令补全、插件事件、初次选服的有界队列已满）用合并的 WARN：第一次立即写，之后每分钟最多一行，带上这段时间里被拒绝的次数。合并在下一次发生时输出，所以安静下来以后不会再补一行。

控制台的 `send`、`kick`、`loglevel` 和 `stop` 以 INFO 写入 logger `moonbridge.admin`，例如 `Console kicks Tester: spamming chat`。

## DEBUG 写什么

协议细节保留在 DEBUG：后端拨号与 TCP 建立、登录帧刷写、加密请求、意外的登录包、帧处理失败的异常栈、WebSocket 升级被拒、控制通道连接结束。排查某一块时只打开那一块：运行中可以在控制台输入 `loglevel transfer debug`（重启后恢复），或长期在 `logback.xml` 中：

```xml
<logger name="dev.moonbridge.core.session.transfer" level="DEBUG" />
```

## 永远不写

- **密钥和凭据**：控制通道 secret、HMAC、Mojang `serverId` 哈希、会话令牌、加密密钥。
- **未清理的对端或插件文本**：用户名、踢出原因、插件提供的拒绝文本都先经过 `LogText.clean`：控制字符和双向文字控制字符替换为 `?`，超过 128 个字符截断并加 `…`。OFFLINE 模式不限制用户名的字符，不清理就能用换行伪造日志行。
- **对端协议异常的原文**：控制通道只写代理自己的固定原因；其他异常记为 `malformed registration`。
- **逐帧、逐包日志**：INFO 及以上从不在数据路径上写。
- **聊天内容和命令参数**。

## 玩家 IP

玩家事件默认带玩家 IP（与 Velocity 相同）。不想在日志中保存玩家地址时：

```yaml
logging:
  playerAddresses: false
```

此时地址写成 `[hidden]`。该开关只影响日志；后端转发和插件 API 中的地址不受影响。WebSocket 玩家的地址是可信前置代理报告的地址，规则见 [WebSocket 接入](websocket.md)。

## 写新日志时

1. 先按上表选级别；拿不准时问「运维看到这行会做什么」——什么也不做就是 DEBUG。
2. 玩家和服务器事件加到 `ConnectionLog`，不要在各处直接写；它负责清理文本和隐藏地址。
3. 可能每秒发生很多次的 WARN 用 `ThrottledWarning`；按状态变化的事件（如服务器不可达）只在状态改变时写。
4. 写进日志的原因用固定文本；必须包含外部文本时经过 `LogText.clean`。

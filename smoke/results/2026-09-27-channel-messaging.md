# Channel 消息服务验收记录（2026-09-27）

## 源码与范围

在 `feature/messages-completion` 上整合 `main` 的 `230f317d0ae5e8b419228a0522901f8fd40d3294`，保留富文本和命令补全，然后将旧通信 API/业务消息帧替换为统一消息服务。设计与安装方式见 [Channel 文档](../../docs/backend-channel-design.md)。远端 main 是否合并应以 PR #9 当前状态为准；本记录不代表生产发布。

## 完整构建与回归

Windows，Zulu Java 25.0.4.1。执行：

```powershell
.\gradlew.bat --no-daemon check --console=plain
```

结果 `BUILD SUCCESSFUL in 5m 6s`，244 项测试，无失败、错误或跳过：

| 模块 | 测试数 |
| --- | ---: |
| proxy-core | 208 |
| proxy-plugin-api | 7 |
| messaging-api | 13 |
| messaging-protocol | 7 |
| backend-channel-client | 7 |
| backend-channel-bukkit | 2 |

覆盖富文本与命令补全既有回归、真实 TCP 的双向/跨后端请求、消息身份及回复关联、发布与多个本地插件订阅者、发送/接收 ACL、乱序回复、超时及容量释放、重连隔离、插件停用、认证拒绝、GOODBYE 目录清理和租约过期。

本轮测试发现并修复了超时回复入队时错误断开会话的竞态，以及注册后立即关闭时读线程抢先关 socket 的竞态。后者增加了 20 轮注册后立即关闭的 socket 回归。Future 完成队列也计入 admission 限额，确定性测试验证排队 128 项时第 129 项返回背压，排空后恢复。

`check` 同时运行安装发行包的帮助、版本和配置验证，并检查 Bukkit 宿主内所有 class 的 major version 不超过 52、共享消息类各有一份、未打包 Bukkit API。另行检查宿主和 proxy API JAR，确认旧 `BackendChannelHandler`、`BackendChannels`、`BackendMessage`、`BackendSendResult` 未残留。

## 实际 Java 8 独立进程

执行：

```powershell
.\smoke\channel-java8.ps1 -Java8Home 'C:\Program Files\Zulu\zulu-8' -Java25Home 'C:\Program Files\Zulu\zulu-25'
```

使用 JDK 25 启动 router，JDK 8 编译并运行两个独立后端客户端；没有 Minecraft 玩家。结果：

```text
JAVA8_CHANNEL_PASS java=1.8.0_492 requests=2 backendSubscribers=3 endToEndIdentity=true
OLD_MESSAGING_CLASSES_ABSENT
```

验证 backend→proxy、backend A→B 请求，回复有独立 UUID、`replyTo` 对应原请求、来源正确，以及同一发布消息到达三个后端插件订阅者。

## 本地已测构件 SHA-256

| 构件（0.1.0-SNAPSHOT） | SHA-256 |
| --- | --- |
| strataproxy-backend-channel-bukkit | `CD061D9AD46313D3F5CE07705C8E180E61831A4C737261C5B40A89FC39AA13C7` |
| backend-channel-client | `85860F2A7BCCC61C6A260776C713F94EFAFD28A9F8AD3BC175D2F0C504143614` |
| messaging-api | `637A0FB1001BB7F59248707EF2E3CAE7FC2658960EBA601ECB2ABE6C0104554D` |
| messaging-protocol | `F195DD6FC9AF4E7A36ACC2D45039FD22D4A451EDAD22D87ABC3A59F257DF3854` |

## 未覆盖

未运行真实 Bukkit/Uranium 服务端装载与游戏主线程验收，未进行真实玩家的富文本视觉/点击测试，未验证跨物理机器部署或生产并发容量。此次没有重新运行 Docker Minecraft 登录烟测；其注册协议版本检查已同步为版本 2。消息服务不提供离线保存、自动重放或 exactly-once 保证。

## CI 转服测试修复（同日）

[CI run 10040](https://git.nest.potatolab.uk:8443/layue13/StrataProxy/actions/runs/10040) 中，新消息模块全部通过，唯一失败为 `SessionTransferTest.clientFrameWaitsForWorldTransitionDuringCutover`。Linux 容器（Temurin 25.0.4、Netty 4.2.2.Final）复现栈显示，失败发生在转服后的 `assertLocalCompletion`，世界切换和双向转发断言已经通过。

测试手动向 pipeline 注入 `channelRead` 后漏发 `channelReadComplete`，留下解码器的 fired-read 标志。后续补全请求被 TCP 分段时，长度前缀不足以组成完整帧，但旧标志使解码器跳过下一次手动读取。修复只补齐测试的完整读事件周期，不改生产代码、不延长超时、不弱化断言。

对照结果：Windows 原测试连续 20 次通过；Linux 原测试已完成的 13 次全部失败（终止第 14 次以继续诊断）；Linux 仅补齐 read-complete 后连续 20 次全部通过。临时重复测试标注和诊断日志均不进入源码；正式回归仍由常规 `check` 执行。

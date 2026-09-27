# StrataProxyBackend 命名与真实 Uranium 验收

时间：2026-09-27 22:13–22:14（Asia/Shanghai）。源码对应本记录所在提交，基于 `cc817c0`。

## 命名及构件

后端宿主插件名改为 `StrataProxyBackend`，入口类为 `dev.strataproxy.bukkit.StrataProxyBackendPlugin`，Gradle/Maven 模块为 `backend-bukkit`，发行包为 `strataproxy-backend-bukkit-0.1.0-SNAPSHOT.jar`。宿主负责后端身份、注册和连接生命周期，并向业务插件提供消息服务；原 `StrataProxyChannels` 名称不足以描述这些职责。当前接入文档与 `depend` 示例同步更新，没有保留旧插件名别名。

传输 SDK `backend-channel-client` 保持其名称。历史验收记录中的旧构件名和哈希保持原样，不能当作本次构件。

## 环境与结果

- Windows 本机；Proxy 使用 Zulu Java 25，两个隔离的 Uranium 实例使用 Zulu Java 8.0_492。
- 实际服务端为 `Uranium-1710-rfg-bridge-fea65fe4d9-server.jar`，使用其 Bukkit API 编译验收业务插件。
- 每个后端安装实际宿主 JAR，以及分别打包的 `AcceptanceProbe` 和 `AcceptanceObserver`，由真实 Bukkit 插件加载器装载。Proxy 运行发行包的 `ProxyMain`，通过正式插件 API 观察目录和发收消息。
- 后端只复制服务端与运行库，使用全新世界、无额外整合包模组、零在线玩家。全部监听地址限定为本机环回地址。
- `gradlew --no-daemon check` 成功：244 项测试，失败、错误和跳过均为 0，包含宿主 Java 8 字节码/打包边界和安装发行包烟测。

| 阶段 | 实际检查 | 结果 |
| --- | --- | --- |
| baseline | 两后端自动注册及准确的游戏地址；三个方向的请求；独立回复 UUID、replyTo 和端点；Proxy 与四个后端业务插件订阅者收到同一消息 ID；Bukkit 回调主线程与零玩家 | PASS |
| reconnect | 终止并重新启动 Proxy，后端自动重连、重新注册，重做请求与广播；订阅者计数增加，排除旧消息造成误判 | PASS |
| disable | 停用 A 的主业务插件，不主动撤销其订阅；宿主自动清理后请求返回 NO_HANDLER，观察插件仍响应、A 仍注册 | PASS |
| b-absent-fast | 向 B 发送正常 stop，在等待进程退出前检查目录于 5 秒内移除，早于 30 秒租约到期；A 仍响应 | PASS |
| b-present | 重新启动 B，自动注册，两个业务插件恢复请求和广播 | PASS |
| b-absent | 强制终止 B，检查租约到期后移除，A 在此期间保持连接和响应 | PASS |
| b-present | 再次启动 B，重新验证目录、请求和广播；最后两后端正常停机 | PASS |

最终输出为 `BACKEND_URANIUM_PASS`，脚本退出码 0，所有本次创建的进程均已结束。实际日志中没有宿主/业务插件装载错误或验收回调错误。

首轮验收辅助插件共用 JavaPlugin 基类，被旧 Bukkit 的全局类缓存重复引用，导致 `Plugin already initialized`。已将它们改为独立入口实现且分别打包，随后以上全部阶段通过；宿主没有因为此问题修改业务逻辑。全新服务端首次缺少封禁/白名单 JSON 的警告不属于插件加载失败。

## SHA-256

| 构件 | SHA-256 |
| --- | --- |
| Uranium 服务端 | `8AC63D595166720E163B07047C76C903ACB673254F3FD473DC122CE98A9517E5` |
| StrataProxyBackend 宿主 | `6C1768407C91019637F0010842F8865938C394EDED76C4ACB8E8B5E048FBF614` |

运行目录：`build/backend-uranium-3f2ac0ae9e9548c2bdd48563c0a63302`。原始进程日志、各阶段结果、测试汇总和已测宿主另存于同工作区的 `StrataProxy-Acceptance/2026-09-27-backend-host`。复现入口见 [测试说明](../../docs/testing.md#后端宿主真实服务端验收)。

这次验收覆盖后端注册和消息服务；不包含富文本的真实玩家视觉/鼠标交互、完整整合包兼容性、跨物理主机网络或生产负载容量测量。

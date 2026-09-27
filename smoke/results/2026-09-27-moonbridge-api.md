# MoonBridge 改名与独立后端 API 验收

日期：2026-09-27。基线：`21011d51bd7ba01cdabd356f290fc83e2659776f`，验证对象为包含本记录的改名/API 拆分提交。

## 变更与构件边界

- 仓库和项目名为 MoonBridge，Java 包为 `dev.moonbridge`，启动命令和配置分别为 `moonbridge`、`moonbridge.yml`。
- 部署插件为 `MoonBridgeBackend`，入口为 `dev.moonbridge.bukkit.MoonBridgeBackendPlugin`。
- 新 `backend-bukkit-api` 只包含 `BukkitMessagingService` 接口，Java class major 为 52；POM 的唯一项目依赖是 compile scope 的 `messaging-api`。Bukkit API 由服务器提供。
- 发行包 `backend-api/` 提供两个编译 API JAR；宿主统一提供运行时 API 类、消息服务、认证连接及生命周期清理。
- 业务验收插件用 Java 8、两个 API JAR 和实际 Uranium Bukkit API 编译，编译路径没有宿主或传输 SDK；插件 JAR 不打包 API。

## 构建验证

`gradlew --no-daemon clean check`：成功，244 项测试，0 失败、0 错误、0 跳过。

API 拆分后再次执行 `check :backend-bukkit-api:generatePomFileForBukkitApiPublication :backend-bukkit-api:sourcesJar :backend-bukkit-api:javadocJar :proxy-core:distZip`：成功；受影响后端测试重新执行，未变测试复用先前结果。API/宿主构件检查、安装发行包的帮助、版本和配置校验全部通过。宿主、API 和发行 ZIP 的条目未残留旧包名。

## 真实运行验收

复现：按 `docs/testing.md` 准备已接受 EULA 的隔离 Uranium 包，执行 `smoke/backend-uranium.ps1 -BundlePath <bundle> -BukkitApiJar <server-jar>`。

- 运行目录：`build/backend-uranium-d6b2681ef29a4b21b0751a5d19903dbc`。
- Java 8 后端、Java 25 Proxy；真实 Uranium 服务端和 Bukkit 插件加载器；两个全新隔离世界，无在线玩家。
- 服务端：`Uranium-1710-rfg-bridge-fea65fe4d9-server.jar`；SHA-256：`8AC63D595166720E163B07047C76C903ACB673254F3FD473DC122CE98A9517E5`。
- `baseline`：双后端自动注册、Proxy/后端间请求、UUID/replyTo/source/target、跨节点广播、多插件订阅和 Bukkit 主线程断言通过。
- `reconnect`：停止并重启 Proxy，后端自动重连注册，通信恢复。
- `disable`：停用一个业务插件后其请求返回 NO_HANDLER，另一插件仍响应，后端仍注册。
- `b-absent-fast`：B 正常关闭后在租约到期前完成注销，A 仍响应。
- `b-present`：B 重启后注册和两个业务插件通信恢复。
- `b-absent`：强制终止 B 后租约到期清理，A 仍响应。
- 第二次 `b-present`：再次启动 B，注册和通信恢复。

最终 `BACKEND_URANIUM_PASS`，脚本退出码 0；脚本关闭并等待其拥有的服务端进程退出。

## 已验收构件 SHA-256

| 构件 | SHA-256 |
| --- | --- |
| `moonbridge-backend-bukkit-0.1.0-SNAPSHOT.jar` | `866CCBB2531C3DE752BE75EA9639A6AB8250B78F59CBFE48006F103E9C887CEF` |
| `backend-bukkit-api-0.1.0-SNAPSHOT.jar` | `628CF6B2E19D94DFF877CA2A4FFF21051466672563A5EE9DD9BEF7A861F784F0` |
| `moonbridge-0.1.0-SNAPSHOT.zip` | `5A73F3FDEEDD528C5FD8BBB933833F580BBEEE5C714E2580D47473FED5155138` |

本记录证明本地构建和实际服务端功能验收；不代表 Maven 已发布、完整整合包兼容、真实玩家富文本显示或生产容量验证。此前 smoke/benchmark 历史记录保留原始名称和路径。

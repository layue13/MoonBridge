# 首次登录路由专项验收

日期：2026-09-27。基线：`fd5e79792ffff03469ff38f827fa9037abfb5329`。设计契约见 [initial-routing.md](../../docs/initial-routing.md)。

## 自动化专项验证

执行 `gradlew --no-daemon :proxy-core:test --tests dev.moonbridge.core.session.InitialRoutingTest --tests dev.moonbridge.app.ProxyConfigurationTest` 成功。会话路由 9 个测试、配置 14 个测试，均为 0 失败、0 错误。插件 API 候选列表值类型测试也已单独通过。

覆盖显式候选顺序与 TCP 失败后切换、无入口时拒绝、插件接管及拒绝/异常/未知目标不回退、后端登录拒绝后不重试、断线后迟到 DNS 失败不连接下一目标、注册换代后旧 socket 收到零个 Minecraft 字节、选服与 DNS 共用总预算，以及实际 PluginHost 异步选服期间新注册目标的实时解析。

总预算测试先在异步选服消耗两秒，再进入未完成的 DNS 查询；四秒总预算不在 DNS 开始时重置。配置/API 测试包含顺序、不可变列表、数量和名称边界，以及旧 `plugins.initialPlacementTimeoutSeconds` 配置拒绝。

## 真实 Uranium 后端

使用已有且已接受 EULA 的最小发行包复制到全新隔离目录，未修改原服务器、世界或插件。后端 Java 8，Proxy 和协议探针 Java 25。

- 服务端：`Uranium-1710-rfg-bridge-fea65fe4d9-server.jar`。
- 服务端 SHA-256：`8AC63D595166720E163B07047C76C903ACB673254F3FD473DC122CE98A9517E5`。
- 命令：`smoke/local-uranium.ps1 -BundlePath build/backend-acceptance-bundle -ProbeClassesPath build/initial-routing-probe-classes`。
- 探针从 Uranium 的 `rfg/src/test/java/cc/uraniummc/rfg/smoke/MinecraftProtocolProbe.java` 编译，模拟 Minecraft 1.7.10/FML 客户端。
- 运行目录：`build/local-uranium-proxy-e14dffa6c8b341d6aa0d14d214d8af6f`。
- 实际 `ProxyMain` 读取 `initialRouting.servers: [uranium]`，代理端口 63733，后端端口 63731。

```text
NETTY_PERSISTENT_CONNECTION_PASS keepAlives=17
NETTY_PROTOCOL_PASS status=2 ping=2 fragmented=true FML_login=true JoinGame=true
REAL_URANIUM_PROXY_PASS serverPort=63731 proxyPort=63733
```

脚本退出码 0，测试拥有的 Proxy 和 Uranium 进程均已结束。真实后端验证显式入口及协议链路；故障切换、插件策略和竞态由上述可控制的 socket/异步测试覆盖。本次未进行真人客户端视觉验收、完整模组包兼容或生产容量测试。全项目回归和远端 CI 结果以本次 PR 的检查记录为准。

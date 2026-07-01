# JavaDoc 参考

本文说明项目源码级 JavaDoc 的组织方式，以及如何在本地生成 API 参考文档。

## 生成

```powershell
.\gradlew.bat javadoc
```

生成的 HTML 会写入各模块的 `build/docs/javadoc` 目录，例如：

- `proxy-api/build/docs/javadoc`
- `proxy-plugin-api/build/docs/javadoc`
- `proxy-network/build/docs/javadoc`
- `proxy-admin-api/build/docs/javadoc`

大规模修改注释后，建议强制重跑，避免 Gradle up-to-date 状态隐藏文档问题：

```powershell
.\gradlew.bat --rerun-tasks javadoc
```

## 文档范围

本项目的 JavaDoc 重点覆盖公开契约：

- `proxy-api`：后端服务器描述、健康状态、负载、drain 策略、注册表契约和路由模型。
- `proxy-plugin-api`：插件生命周期、命令、事件、调度器、玩家服务和服务器服务契约。
- `proxy-routing`：路由请求、路由决策、健康和权重感知路由诊断。
- `proxy-protocol`：包元数据、协议状态、方向、标记和包分类。
- `proxy-codec-minecraft`：Minecraft VarInt、压缩帧、加密、zstd 和 custom payload 辅助工具。
- `proxy-network`：Netty 服务生命周期、连接准入控制、认证运行时、转发运行时、状态响应运行时和玩家转服结果。
- `proxy-admin-api`：注册表持久化、Admin 注册表变更、转服服务、HTTP 视图、指标和诊断 DTO。
- `proxy-observability`：指标事件、内存事件 sink、计数器、样本和快照 DTO。
- `proxy-bootstrap`：YAML 加载、运行时配置记录和校验结果。
- `proxy-compression`、`proxy-packet-analysis`、`proxy-native`、`proxy-command`、`proxy-plugin`：策略、规则、运行时、命令、事件和插件加载契约。

包私有 relay handler 和底层实现辅助类只在行为不直观、或影响公开契约时补充注释。

## 维护规则

- 公开接口、公开 record、公开 enum、公开 class 和公开构造器都应有 JavaDoc。
- record 的 JavaDoc 应说明字段语义、单位和默认行为。
- 返回 Netty `ByteBuf` 所有权的方法必须说明由谁释放。
- 跨模块传播的诊断字符串、拒绝原因、阈值和超时单位应在边界处说明。
- 英文版文档位于 [../javadoc.md](../javadoc.md)，结构与本文保持同步。

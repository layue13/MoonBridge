# JavaDoc 参考

本文说明项目源码级 JavaDoc 的组织方式，以及如何在本地生成 API 参考文档。

## 生成

```powershell
.\gradlew.bat javadoc
```

生成的 HTML 会写入各模块的 `build/docs/javadoc` 目录，例如：

- `proxy-core/build/docs/javadoc`
- `proxy-plugin-api/build/docs/javadoc`
- `proxy-app/build/docs/javadoc`

大规模修改注释后，建议强制重跑，避免 Gradle up-to-date 状态隐藏文档问题：

```powershell
.\gradlew.bat --rerun-tasks javadoc
```

## 文档范围

本项目的 JavaDoc 重点覆盖公开契约：

- `proxy-core`：后端服务器描述、路由、协议元数据、Minecraft codec、Netty 生命周期、指标、诊断和运行时策略。这些包是代理内部实现，不单独作为 API 发布。
- `proxy-plugin-api`：插件生命周期、命令、事件、调度器、玩家服务和服务器服务契约。
- `proxy-plugin`：运行时插件加载和生命周期管理。
- `proxy-app`：YAML 加载、配置校验、注册表持久化、管理 HTTP 视图、指标和诊断 DTO。

包私有 relay handler 和底层实现辅助类只在行为不直观、或影响公开契约时补充注释。

## 维护规则

- 公开接口、公开 record、公开 enum、公开 class 和公开构造器都应有 JavaDoc。
- record 的 JavaDoc 应说明字段语义、单位和默认行为。
- 返回 Netty `ByteBuf` 所有权的方法必须说明由谁释放。
- 跨模块传播的诊断字符串、拒绝原因、阈值和超时单位应在边界处说明。
- 正式用户文档统一维护在 `docs/` 根目录；历史验证资料仅保存在源码仓库中，不随发布包分发。

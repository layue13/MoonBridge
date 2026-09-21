# StrataProxy Bukkit 后端适配器

这是独立的 Java 8 Bukkit 插件，不属于 StrataProxy 的 Gradle 多模块工程。它以 Bukkit/Spigot 1.8 API 编译，但只调用 Bukkit 1.7.10 已有的基础 API，因此可运行在 1.7.10 Bukkit 派生端。它在启动时向代理的 `backendAgent` 端点注册本服务器，随后发送心跳；关闭时可主动下线。

先在代理配置启用 `backendAgent`，并设置至少 32 字符的随机 `sharedSecret`。然后将同一密钥写入本插件 `config.yml`，按实际服务器修改 `backend.name`、`backend.address`、标签、容量和持久化选项。

构建：`gradle jar`。将生成的 `build/libs/strataproxy-bukkit-backend-agent-*.jar` 放入 Bukkit 的 `plugins/` 目录后重启。

该端点只接受注册、心跳和下线，不能读取玩家、执行命令或修改其他代理设置。

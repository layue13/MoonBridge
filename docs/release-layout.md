# 发行物与安装位置

`bash ./gradlew release`（Windows：`.\gradlew.bat release`）把部署需要的东西收集到 `build/release/`：

| 路径 | 是什么 | 装在哪里 |
| --- | --- | --- |
| `server/moonbridge-<版本>.zip` | 代理发行包：可直接运行的 `moonbridge.jar`、`config/` 示例配置、README；带 `-Pluckperms` 构建时 `plugins/` 里含 LuckPerms 平台插件 | 代理服务器，解压后在该目录用 JDK 25 运行 `java -jar moonbridge.jar` |
| `backend/moonbridge-backend-<版本>.jar` | 后端共享宿主 `MoonBridgeBackend`（Bukkit/Uranium 插件，Java 8），内含消息 API 与控制通道客户端 | 每台后端服务器的 `plugins/`；后端业务插件对它 `compileOnly` 并在 `plugin.yml` 里 `depend: [MoonBridgeBackend]` |
| `client/moonbridgews-<版本>.jar` | 1.7.10 Forge WebSocket 客户端 mod | 玩家客户端 `mods/`，另需 UniMixins；只在使用 `ws://`/`wss://` 地址时需要 |

可选开关：

- `-Pluckperms`：同时编译 LuckPerms 平台插件（需要 `git submodule update --init --recursive`），并运行 `permissionAcceptance`。CI 和正式发布都带这个开关。
- `-PskipClientMod`：不构建客户端 mod（它需要下载 Minecraft 1.7.10 工具链）。

Maven 坐标（group `uk.potatolab.moonbridge`）：`proxy-plugin-api`（代理插件 API）、`messaging`（消息 API 与协议）、`backend`（后端宿主及其 API）、`luckperms-moonbridge`（LuckPerms 平台插件）。版本与仓库见[发布与依赖版本](publishing.md)。

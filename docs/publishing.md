# 发布与依赖版本

MoonBridge 当前区分本地开发版本、CI 发布的不可变开发构件和发行包。尚未选定稳定版版本号；CI 的 Maven 发布也不会自动创建 Git tag 或 Release。

## CI 发布流程

`.github/workflows/ci.yml` 在推送 `main` 或手动触发时运行。工作流以 CI run ID、attempt 和提交的前 12 位 SHA 生成版本 `0.1.0-dev.<run-id>.<attempt>.<commit>`，然后按顺序运行：

1. 使用 JDK 25 运行 `gradlew -Pluckperms check release`。
2. 将 `build/release/`（服务器包、后端插件、客户端 mod，见[发行物与安装位置](release-layout.md)）上传为名为 `moonbridge-development` 的 CI 工件，保留 30 天。
3. 执行 `gradlew -Pluckperms publish`，发布四个 Maven 模块。

Maven 发布需要 CI secrets `MAVEN_USER` 和 `MAVEN_PASSWORD`。验证构建和发行 ZIP 的步骤先运行；凭据缺失会使发布步骤失败。CI 成功状态是发布结果的依据；CI 工件下载和 Maven 包仓库是不同交付物。工作流不创建 Git Release，也不表示已有稳定版本。

本地 Gradle 默认版本为 `0.1.0-SNAPSHOT`。如需本地构建另一个版本，可在 Gradle 命令中传入 `-PreleaseVersion=<version>`；不要将本地 SNAPSHOT 当作不可变的 Maven 发布版本。

## 构件

Maven 坐标统一使用 group `uk.potatolab.moonbridge` 和 CI 所报版本：

| Artifact ID | 用途 |
| --- | --- |
| `proxy-plugin-api` | Proxy 插件 API |
| `messaging` | 跨节点消息公共 API 与线协议（Java 8） |
| `backend` | 后端共享宿主 `MoonBridgeBackend`（Bukkit/Uranium 插件，Java 8），含其服务 API 与控制通道客户端；业务插件对它 `compileOnly` |
| `luckperms-moonbridge` | Proxy 原生 LuckPerms 插件；不是业务插件的编译 API |

四个模块均随同一次 CI 发布使用相同版本。业务插件将 `backend` 和目标服务器的 Bukkit API 作为 `compileOnly` / Maven `provided` 依赖；服务器提供 Bukkit API，`MoonBridgeBackend` 宿主提供运行时消息 API 和服务。不要把 `backend` 打包进插件。非 Bukkit 宿主也使用 `backend` 中的控制通道客户端；见[后端接入说明](backend-channel-design.md)。

旧坐标 `messaging-api`、`messaging-protocol`、`backend-channel-client`、`backend-bukkit-api`、`backend-bukkit` 已停止发布：前两者合并为 `messaging`，后三者合并为 `backend`，包名与类名不变，只需替换依赖坐标并重新编译。

包仓库地址为 `https://git.nest.potatolab.uk:8443/api/packages/layue13/maven`。消费者应从成功 CI 记录中复制实际版本；以下 `<published-version>` 只是需替换的说明占位符：

```kotlin
repositories {
    maven("https://git.nest.potatolab.uk:8443/api/packages/layue13/maven")
}

dependencies {
    compileOnly("uk.potatolab.moonbridge:backend:<published-version>")
}
```

```xml
<dependency>
  <groupId>uk.potatolab.moonbridge</groupId>
  <artifactId>backend</artifactId>
  <version>&lt;published-version&gt;</version>
  <scope>provided</scope>
</dependency>
```

不要将 `<published-version>` 原样放入构建文件。CI 版本形如 `0.1.0-dev.10076.1.0123456789ab`，其中各段来自该次 run、重试次数和提交 SHA。Gitea Maven 包不能覆盖同名版本；重跑已失败或中断的发布时会生成新的 attempt 版本，消费者应选用实际成功发布的那一版。

## Proxy 发行包

`:proxy-core:distZip` 生成 `proxy-core/build/distributions/moonbridge-<version>.zip`。CI 把这个 ZIP 作为 `moonbridge-development` 下载工件的一部分上传，保留 30 天；它不是 Maven 模块，也不自动成为 Git Release。解压后从发行包目录运行：

```powershell
.\bin\moonbridge.bat --validate-config .\config\moonbridge.yml
.\bin\moonbridge.bat --config .\config\moonbridge.yml
```

Linux/macOS 使用 `bin/moonbridge`。发行包根目录只有运行需要的内容：`bin/` 启动脚本、`lib/` 依赖、`config/` 配置模板、README，以及带 `-Pluckperms` 构建时的 `plugins/`。文档、验收与基准记录留在仓库里；后端宿主 jar 不在代理包内，见[发行物与安装位置](release-layout.md)。

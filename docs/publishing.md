# 发布与依赖版本

MoonBridge 当前区分本地开发版本、CI 发布的不可变开发构件和发行包。尚未选定稳定版版本号；CI 的 Maven 发布也不会自动创建 Git tag 或 Release。

## CI 发布流程

`.gitea/workflows/ci.yml` 在推送 `main` 或手动触发时运行。工作流以 CI run ID、attempt 和提交的前 12 位 SHA 生成版本 `0.1.0-dev.<run-id>.<attempt>.<commit>`，然后按顺序运行：

1. 使用 JDK 25 运行 `gradlew check :proxy-core:distZip`。
2. 将 JAR 和发行 ZIP 上传为名为 `moonbridge-development` 的 CI 工件，保留 30 天。
3. 执行 `gradlew publish`，发布七个 Maven 模块。

Maven 发布需要 Gitea Actions secrets `MAVEN_USER` 和 `MAVEN_PASSWORD`。验证构建和发行 ZIP 的步骤先运行；凭据缺失会使发布步骤失败。CI 成功状态是发布结果的依据；CI 工件下载和 Maven 包仓库是不同交付物。工作流不创建 Git Release，也不表示已有稳定版本。

本地 Gradle 默认版本为 `0.1.0-SNAPSHOT`。如需本地构建另一个版本，可在 Gradle 命令中传入 `-PreleaseVersion=<version>`；不要将本地 SNAPSHOT 当作不可变的 Maven 发布版本。

## 构件

Maven 坐标统一使用 group `uk.potatolab.moonbridge` 和 CI 所报版本：

| Artifact ID | 用途 |
| --- | --- |
| `proxy-plugin-api` | Proxy 插件 API |
| `messaging-api` | 跨节点消息公共 API |
| `messaging-protocol` | 消息传输协议 |
| `backend-channel-client` | 非 Bukkit 后端宿主使用的传输 SDK |
| `backend-bukkit-api` | Bukkit 业务插件编译时使用的服务 API |
| `backend-bukkit` | 部署在 Bukkit/Uranium 服务端的共享宿主实现 |
| `luckperms-moonbridge` | Proxy 原生 LuckPerms 插件；不是业务插件的编译 API |

七个模块均随同一次 CI 发布使用相同版本。业务插件通常将 `backend-bukkit-api` 和目标服务器的 Bukkit API 作为 `compileOnly` / Maven `provided` 依赖；服务器提供 Bukkit API，`MoonBridgeBackend` 宿主提供运行时消息 API 和服务。普通业务插件不应依赖宿主/传输 SDK 实现，也不应把任何 API JAR 打包进插件。非 Bukkit 宿主才需要 `backend-channel-client` 及消息 API/协议；见[后端接入说明](backend-channel-design.md)。

包仓库地址为 `https://git.nest.potatolab.uk:8443/api/packages/layue13/maven`。消费者应从成功 CI 记录中复制实际版本；以下 `<published-version>` 只是需替换的说明占位符：

```kotlin
repositories {
    maven("https://git.nest.potatolab.uk:8443/api/packages/layue13/maven")
}

dependencies {
    compileOnly("uk.potatolab.moonbridge:backend-bukkit-api:<published-version>")
}
```

```xml
<dependency>
  <groupId>uk.potatolab.moonbridge</groupId>
  <artifactId>backend-bukkit-api</artifactId>
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

Linux/macOS 使用 `bin/moonbridge`。发行包根目录还带有 README、`docs/`、`smoke/results/` 和 `benchmarks/results/`，这些随包文档和证据记录可离线阅读。指向源码、测试和脚本的链接需要另有仓库 checkout；配置模板位于发行包 `config/` 目录。

# MariaDB 13.0.2 权限数据库

MoonBridge 的 SQL 存储位于原生 LuckPerms 平台插件：保存用户权限、组、继承、track 和管理记录，同时可提供 SQL 消息同步。生产目标为 MariaDB Server 13.0.2。新生成的 LuckPerms 配置默认选择 `storage-method: mariadb`；启用插件前需要配置共享数据库。代理核心没有另一个独立 JDBC 数据库。

LuckPerms 使用其原生 MariaDB 存储实现、表结构和连接工厂，按 `data.address`、数据库名及凭据生成 `jdbc:mariadb:` 连接。固定的官方上游版本由依赖管理器下载、校验和隔离 MariaDB Connector/J **3.5.2**。这是 LuckPerms 自身管理的驱动，不是由 MoonBridge 普通 Gradle runtime 依赖打包的驱动；服务端版本 **13.0.2** 与驱动版本分别管理。

配置示例、插件数据目录和自动 SQL/可选 Redis 同步见 [权限文档](permissions.md)。远程部署使用 `data.pool-settings.properties.sslMode: verify-full`，并按 [MariaDB TLS 文档](https://mariadb.com/docs/connectors/mariadb-connector-j/using-tls-ssl-with-mariadb-java-connector) 配置和验证数据库身份；本机开发示例及隔离验收显式使用 `disable`。

## 已有数据与部署

已有 `config.yml` 不会被默认资源覆盖。手动更新 `storage-method: mariadb`、数据库连接信息和 TLS 属性，保留原有 `table-prefix` 与权限数据。不要因为切换驱动删除表或创建一个空的权限数据库替代已有数据。

PDS、AetherShard 与 LuckPerms 可以使用同一台 MariaDB 服务，各自保留独立数据库/表与数据权威。多台 MoonBridge 代理使用同一个 LuckPerms 数据库和表前缀，各自设置不同的 `server`。

数据库服务迁移需要另行停写、备份、独立恢复并核验用户、组和继承数据，然后统一切换所有代理及共用该数据的 Bukkit LuckPerms。保留源数据库作为回退来源；禁止同时向两套数据库写入。本次代码适配不执行现有服务的数据迁移。

## 真实数据库验收

需要 JDK 25、Node.js 和 Docker。先初始化固定上游并构建分发包，普通 `check` 保留显式 H2 的离线内部验收：

```sh
git submodule update --init --recursive
bash ./gradlew clean check :proxy-core:distZip --no-daemon --max-workers=2
mkdir -p build/permission-network-classes
javac -cp 'proxy-core/build/install/moonbridge/lib/*' -d build/permission-network-classes smoke/PermissionNetworkAcceptance.java
node smoke/permission-services.cjs start
```

运行下面两轮后，务必执行 stop（失败也需清理）：

```sh
node smoke/permission-network.cjs sql outage
node smoke/permission-network.cjs redis outage
node smoke/permission-services.cjs query
node smoke/permission-services.cjs stop
```

fixture 只创建带本轮所有权标签的本机容器，固定使用 `mariadb:13.0.2` 并检查实际服务器版本。SQL 模式使用 MariaDB 原生自动 SQL messenger；Redis 模式仍使用 MariaDB 保存权限。测试执行双实例授权/撤销/组继承、上下文、临时权限、插件重启持久化，以及 MariaDB/Redis 停机恢复。普通构建不会自动执行这些外部服务测试。

stop 删除本轮容器和数据卷、移除凭据文件，并对保留的测试配置密码脱敏。[当前验收记录](../smoke/results/2026-10-02-mariadb.md) 与早期 MySQL/H2/游戏客户端证据分别记录，避免将旧运行结果当成本次 MariaDB 联测。

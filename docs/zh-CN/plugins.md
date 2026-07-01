# 插件和游戏内命令

StrataProxy 会从当前配置文件旁边的 `plugins/` 目录加载代理插件。插件 jar 是可选的；目录不存在时代理照常启动。

## 内置玩家命令

这些命令由代理拦截，不会继续转发到后端服务器：

```text
/server <server>
/hub
/lobby
/servers
/glist
```

`/server`、`/hub` 和 `/lobby` 使用和 `strataproxy-admin players transfer`、受支持 BungeeCord `Connect` plugin message 相同的类 Bungee 后端重登录转服流程。

## 插件入口

插件 jar 可以在 jar 根目录放置 `strataproxy-plugin.properties`：

```properties
id=example
name=Example Plugin
version=1.0.0
main=com.example.ExamplePlugin
```

也可以使用标准 Java `ServiceLoader`，提供 `dev.strataproxy.plugin.ProxyPlugin` provider。

插件可以通过 `PluginContext` 注册命令、订阅事件、查询玩家和服务器、调度异步任务、发起玩家转服。插件代码不要阻塞 Netty event loop。

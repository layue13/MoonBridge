# 快速开始

StrataProxy 是面向 Minecraft 的静态后端代理：监听客户端连接、按路由选择后端，并处理认证、转发协议、压缩和 Forge/Bungee 兼容所需的协议帧。

## 构建与校验

```powershell
.\gradlew.bat --no-daemon check :proxy-core:installDist
```

代理程序在 `proxy-core\build\install\strataproxy\`。

## 配置并启动

```powershell
Copy-Item proxy-core\src\main\resources\config\strataproxy.yml .\strataproxy.yml
.\proxy-core\build\install\strataproxy\bin\strataproxy.bat --config .\strataproxy.yml --validate-config
.\proxy-core\build\install\strataproxy\bin\strataproxy.bat --config .\strataproxy.yml
```

至少配置一个 `servers` 后端。修改 YAML 后重启代理生效；没有 HTTP 管理端口或管理令牌。动态后端仅由已加载插件通过 API 注册、下线或切换 drain 状态。

详见[配置说明](configuration.md)和[运维说明](operations-manual.md)。

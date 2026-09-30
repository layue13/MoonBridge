# 设计文档

文档描述当前实现；源码和自动化测试是行为核对依据。各验收记录注明了自己的日期、提交和环境，详见[验证方法](testing.md)。

## 从这里开始

- 要运行 Proxy：先看[运行配置](operations.md)，再按部署需求修改认证方式、入口候选和后端地址。
- 要开发插件：先看[插件开发](plugins.md)；后端 Bukkit 插件另看[后端 Channel 接入](backend-channel-design.md#后端插件接入)。
- 要理解初次登录为什么拒绝或如何重试：看[初次登录路由](initial-routing.md)及其[专项验收记录](../smoke/results/2026-09-27-initial-routing.md)。
- 要核对证据覆盖面：看[验证方法](testing.md)，并按各记录注明的日期、提交、环境和探针类型判断。

| 文档 | 内容 |
| --- | --- |
| [架构与边界](architecture.md) | 模块职责、会话状态、线程模型、转服、目录所有权 |
| [消息与命令补全](messages-and-completion.md) | 富文本、模板、异步参数补全及协议边界 |
| [插件开发](plugins.md) | 生命周期、玩家/服务器视图、选服、命令与统一类型化事件 API |
| [转服协调](transfer-coordination.md) | 登录前决策、可选源服释放、异步确认、期限与失败边界 |
| [玩家权限与 LuckPerms](permissions.md) | 通用权限接口、提供者生命周期、命令鉴权和原生 LuckPerms 平台插件 |
| [初次登录路由](initial-routing.md) | 显式入口、插件策略、超时、重试安全边界与验收契约 |
| [玩家操作与部署完善设计](player-operations.md) | 消息、主动断开、服务器列表配置与验收边界 |
| [运行配置](operations.md) | 认证、接入限制、超时、启动和部署边界 |
| [后端 Channel 消息服务](backend-channel-design.md) | 共享宿主、跨后端路由、消息身份与交付语义 |
| [Channel 消息设计契约](channel-messaging-v2.md) | 从参与者、寻址、响应与失败语义推导 API 和验收条件 |
| [验证方法](testing.md) | 自动化测试、烟测、真实客户端与合成性能测量 |
| [发布与依赖版本](publishing.md) | 本地 SNAPSHOT、CI 不可变版本、Maven 构件和发行包的区别 |

## 当前可引用的验证记录

- [初次路由专项验收](../smoke/results/2026-09-27-initial-routing.md)：自动化路由覆盖与真实 Java 8 Uranium 后端协议探针；不是完整 Prism 模组包视觉验收。
- [MoonBridge 后端 API 验收](../smoke/results/2026-09-27-moonbridge-api.md)：隔离 Uranium 服务端中的 Bukkit 宿主和 API 功能验收；无玩家。
- [2026-09-26 Prism 历史记录](../benchmarks/results/2026-09-26-prism-pack.md)：真实客户端及配对测量，仅适用于该记录描述的代码版本和环境。

设计约束：核心管理连接和协议；插件负责玩法与访问策略。`ServerView` 是后端目录快照，发现方式是目录的提供者，不引入核心负载观测。准入与会话通知使用同一事件类型、监听器和注册表；不同事件在派发策略上处理结果。常规 PLAY 转发没有通用事件派发。

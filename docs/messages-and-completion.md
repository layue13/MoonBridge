# 消息构造与命令补全

## 目标

插件用统一组件表达消息，用简单回调执行命令和补全。代理负责协议 5 编码、连接身份、线程切换和有界资源。消息模板、权限、业务参数与候选过滤由插件决定。

## 富文本

API 导出 Adventure Component 与 MiniMessage。`Players.sendMessage`、`Players.disconnect`、`CommandInvocation.reply`、`AccessDecision.deny` 和 `PlacementDecision.reject` 接受 Component；String 重载表示纯文本，绝不自动解释标签。固定组件可提前构造并复用。

```java
var message = Component.text("返回主城", NamedTextColor.GREEN)
    .hoverEvent(Component.text("点击返回主城"))
    .clickEvent(ClickEvent.runCommand("/spawn"));
context.players().sendMessage(player.identity(), message);

var greeting = MiniMessage.miniMessage().deserialize(
    "<green>欢迎 <player>！</green>",
    Placeholder.unparsed("player", player.username()));
invocation.reply(greeting);
```

动态字符串通过 `Placeholder.unparsed` 插入；组件通过 `Placeholder.component` 插入。插件显式解析模板，解析与组件构造放在插件工作线程。普通后端消息仍沿用转发路径。

1.7.10 支持文字、翻译组件、16 色与文字样式、打开网址、执行命令、填入命令和悬浮文字。RGB 映射到最近的传统颜色；不支持的组件、样式或事件明确拒绝。当前不提供物品/实体悬浮适配。LOGIN/PLAY 使用相同 JSON 编码；踢出界面不保证聊天窗口的交互能力。

树深度最多 32、节点最多 256，编码后的 JSON 最多 32,767 UTF-8 字节；悬浮文本和翻译参数也计入树限制。纯文本快捷入口最多 1,024 个 Unicode 码点。`Players` 在调用线程验证、编码，再将编码结果提交玩家 EventLoop；不在消息写入时重复解析模板。既有背压、最多 64 条未完成消息和连接代次检查继续生效。

## 插件命令

```java
context.commands().register("server", invocation -> {
    invocation.reply(Component.text("当前后端：")
        .append(Component.text(invocation.player().currentServer().orElse("未连接"),
                               NamedTextColor.YELLOW)));
}, completion -> {
    String args = completion.arguments();
    String prefix = args.substring(args.lastIndexOf(' ') + 1);
    return CompletableFuture.completedFuture(context.servers().all().stream()
        .map(ServerView::name)
        .filter(name -> name.startsWith(prefix))
        .toList());
});
```

两参数 `register(name, handler)` 仍适合无需参数补全的命令。三参数版本增加 `CommandCompleter`，返回 `CompletionStage<List<String>>`，可接数据库或其他异步查询。`CommandCompletion` 提供玩家快照、无斜杠的根命令名和根命令后去掉一个分隔字符的参数原文；末尾空格保留。`/server ` 会以空参数调用补全器。

候选是客户端用于替换最后一个词的字符串；前缀过滤由插件处理。最多返回 100 项，去重，忽略空值、空字符串、含空白或超过 100 个 UTF-16 单元的候选，并限制总字节数。插件负责命令执行的权限检查；补全器应只返回允许当前玩家看到的候选。根命令名本身公开可补全，不提供隐式权限系统。

## 线程与协议归属

- 插件执行与补全回调在有界命令工作池运行，补全等待最多 1 秒，全宿主最多 128 个未决请求。异常、超时、卸载或过载得到空候选；关闭注册或玩家连接会尽力取消异步任务。
- `/prefix` 请求转给后端，返回时加入匹配的代理根命令并去重。已注册代理命令的参数只由代理插件补全，即使没有补全器也不泄漏到后端。未知命令和普通玩家名补全转给后端；正常返回包保持原始字节。
- 1.7.10 没有补全请求 ID。每连接同时处理一个请求，额外排队最多 8 个；超出队列限制关闭连接，避免无限积压。按请求顺序回答，协议本身无法识别客户端已经编辑了哪一个输入框状态。
- 后端 1 秒未回复时，根补全先返回代理候选，其他请求返回空候选。保留一个迟到回复标记；在收到并丢弃该回复前，不再向该后端发补全请求，以免把旧回复归给新请求。插件补全仍可用。转服切换后使用新的状态。
- 切换后端、断线和主动踢出关闭旧补全状态，释放暂存帧并取消插件等待。普通 PLAY 帧只检查包 ID，不解析、复制或派发补全回调。

## 验收

组件与协议测试覆盖颜色、点击、悬浮、翻译参数、转义、尺寸/深度限制和不支持的能力。补全测试覆盖根合并、参数末尾空格、后端透传、异步完成、超时、迟到回复隔离及关闭清理；真实 TCP 测试覆盖发行路径使用的会话和插件宿主连线。构建与测试结果记录在工作交付中，客户端视觉交互单独标明是否完成实测。

本机额外用 Java 8 加载 Uranium 实际组件解析器，读取当前发行包生成的富文本 JSON；正文、点击命令 /spawn 和悬浮正文均匹配。所用 Uranium JAR SHA-256 为 B16747D08BAD4B7C67DB8F1F41C9C31066FEAAAC22B527775F604BDFA885BAD2。该检查不启动客户端，不证明鼠标交互或渲染效果已实测。

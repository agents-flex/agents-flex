---
title: 上下文管理
description: 管理业务 ChatMemory、消息窗口、摘要、多模态内容和工具结果边界。
---

# 上下文管理

## 概述

Agent 的上下文同时面对两个目标：保留足够历史以正确决策，又避免消息和工具结果无限增长。Framework 在模型调用前构建独立的上下文窗口：按完整 Turn 选择历史，使用 `maxAttachedTurns` 控制语义范围，使用 `maxAttachedMessages` 作为消息数量上限，并可将较早的已完成工具 Turn 归一化为 UserMessage + 最终 AiMessage。该过程不会清空或重写 `ChatMemory`。

## 消息的三个层次

- 业务 `ChatMemory`：由应用维护，跨多轮 Turn 保存历史。
- `AgentTurn` 的 `MemoryPrompt`：本次任务的协议消息和系统指令。
- 模型调用 Prompt：依据 `maxAttachedTurns`、`maxAttachedMessages` 和工具 Turn 压缩规则生成的当前视图。

`ChatMemory.getMessages(count)` 返回页面使用的完整时间线；`getModelMessages(count)` 先排除
`modelVisible=false` 的 UI 消息，再对模型消息应用数量限制。窗口策略只影响一次模型调用视图，不会
删除 Turn、Snapshot 或业务 `ChatMemory` 中的原始消息。

## 业务会话管理

```java
ChatMemoryProvider memoryProvider = id -> loadMemory(id);

AgentRunner runner = AgentRunner.builder()
    .chatMemoryProvider(memoryProvider)
    .build();
AgentTurn turn = runner.run(agentId, conversationId, new UserMessage("继续处理"));
```

应用负责持久化 conversationId、`ChatMemory` 和当前未结束的 turnId。Runner 会在 Store 支持会话原子保护时
拒绝同一业务会话的并发新 Turn；业务系统仍应捕获 `AgentConversationBusyException`，决定返回冲突、排队
或合并消息。阻塞时必须按已保存的 turnId 恢复原 Turn，完成后再开始下一轮。`ChatMemoryProvider` 只定位 Memory，
不创建 conversationId，也不拥有会话生命周期。Provider 未配置时，应用也可以继续显式传入历史并
自行回写。

自定义持久化 `ChatMemory` 应实现以下查询和写入语义：

- `getMessages(offset, count)` 从最新消息向前分页，页内仍按从旧到新排列。
- `getMessage(messageId)` 使用主键或索引读取单条消息。
- `addMessageIfAbsent` 按稳定 `messageId` 原子幂等追加。
- `updateMessage(message, expectedVersion)` 按消息 ID 和版本 CAS 更新。

接口为兼容已有实现提供默认分页和查询逻辑，但数据库实现应覆盖它们，避免逐步扩大尾部查询。审批消息
需要持久化更新时必须覆盖 `updateMessage`。Runner 不会调用 `clear()`。

## 模型读取窗口

```java
Agent agent = Agent.builder("support-agent")
    .chatModel(chatModel)
    .maxAttachedTurns(5)
    .maxAttachedMessages(40)
    .compressionPolicy(AgentContextCompressionPolicy.builder()
        .compactCompletedToolTurns(true)
        .keepRecentTurns(2)
        .compressor(messages -> summarizeForModel(messages))
        .build())
    .build();
```

默认最多附加最近 10 个完整 Turn 和 100 条消息。`maxAttachedTurns` 是主要的语义窗口，
`maxAttachedMessages` 是安全上限；框架不会从 ToolCall/ToolMessage 中间硬截断。单个当前 Turn
即使超过消息上限，也会保留完整协议，避免模型收到孤立的 ToolMessage 或未闭合 ToolCall。

`compressionPolicy` 中的 `compactCompletedToolTurns` 只控制较早、已经完成且包含工具调用的 Turn 是否删除中间 `ToolCall/ToolMessage`，仅保留 `UserMessage + 最终 AiMessage`。`keepRecentTurns`（默认 2）会保护最近的若干完整 Turn，这些 Turn 保留原始协议，不参与消息压缩；当前 Turn 始终属于保护范围。

`compressionPolicy` 中的 `compressor` 接收较早且允许压缩的模型可见消息，返回要放入本次模型 Prompt 的消息。它只影响模型上下文，不修改 ChatMemory、Turn 或 Snapshot。返回结果必须以 `UserMessage` 开始，不能包含 UI 消息，并保持每个 `ToolMessage.toolCallId` 与前面 AiMessage 中 ToolCall ID 匹配。未配置摘要器时不会调用摘要模型，只执行已开启的工具 Turn 归一化。

`compressionPolicy` 中的 `compactCompletedToolTurns` 是独立的归一化选项。开启后，较早已完成工具 Turn 会先被压缩为
`UserMessage + 最终 AiMessage`；如果同时配置了 `compressor`，语义压缩器接收的就是这个压缩后的结果。
未配置 `compressor` 时，规则压缩仍然单独生效，不依赖语义压缩器。

框架提供了几个无需额外模型调用的策略：`AgentContextCompressors.identity()` 原样复制消息，
`compactCompletedTurns()` 每轮只保留用户问题和最终 AI 回复，`textExcerpt(maxCharacters)` 提取历史文本为
单条摘要用户消息，`chain(...)` 可组合多个策略。生产环境通常应使用业务侧摘要模型实现
`AgentContextCompressor`，并在摘要失败时保留原始历史。

框架也提供 `AgentContextCompressors.model(...)` 适配聊天模型：

```java
AgentContextCompressor compressor = AgentContextCompressors.model(
    summaryModel,
    "请压缩历史对话，保留业务事实、实体 ID、用户约束、审批结果和未完成事项，不要编造信息。");

Agent agent = Agent.builder("support-agent")
    .compressionPolicy(AgentContextCompressionPolicy.immediate(compressor))
    .build();
```

该策略只对较早已完成 Turn 调用一次摘要模型，模型返回内容会被包装为合法的
`UserMessage + AiMessage`。摘要模型不应注册业务工具；压缩失败时 Runner 会中止本次模型调用，
业务侧可以记录错误并重试或暂时关闭语义压缩。

需要保留每条消息的 User/AI 交替结构时，使用逐消息摘要策略：

```java
AgentContextCompressor compressor = AgentContextCompressors.perMessageModel(
    summaryModel,
    "请压缩每条消息，保留事实、数字、实体 ID、用户约束和决定；不要改变消息角色。"
);
```

它对一批历史只调用一次模型，并要求模型返回：

```json
[
  {"messageId":"u1","summary":"用户咨询订单状态"},
  {"messageId":"a1","summary":"助手说明订单正在配送"}
]
```

框架据此复制原消息的角色、`messageId` 和 metadata，只替换正文，因此得到 `UserMessage -> AiMessage`
的原始交替结构。包含 ToolCall 的 AiMessage 和 ToolMessage 始终原样保留，不能逐条摘要。

如果不希望每次都重新摘要全部旧消息，可以使用增量策略：

```java
AgentContextCompressors.Incremental compressor =
    AgentContextCompressors.incremental(summaryModel, "保留事实、ID、约束和未完成事项");

// 每次调用后，把这两个值保存到业务侧的会话摘要表；恢复会话时再调用 restore。
List<Message> summary = compressor.getSummary();
String coveredUntil = compressor.getCoveredUntilMessageId();
compressor.restore(summary, coveredUntil);
```

增量策略是“整体摘要更新”，而不是逐消息摘要。它按照稳定的 `messageId` 找到上次已覆盖的位置，只把新增的旧消息和既有摘要再次提交给摘要模型；
相同历史重复调用不会重复请求模型。摘要状态应与 `conversationId` 绑定并由业务侧持久化，Runner 不会替业务系统保存它。

### 按触发条件增量持久化

当压缩条件不是固定的消息数量（例如 Token 总量、Turn 数、工具结果大小、时间间隔或租户配额）时，使用协调器把“何时压缩”和“如何压缩”分开：

```java
Agent agent = Agent.builder("support-agent")
    .chatModel(chatModel)
    .compressionPolicy(AgentContextCompressionPolicy.incremental(
        compressionStateStore,
        input -> input.getEstimatedPendingTokens() >= 100_000,
        AgentContextCompressors.model(summaryModel, "保留事实、ID、约束和未完成事项"),
        messages -> tokenCounter.estimate(messages)))
    .build();

// Runner 会按 conversationId 自动读取历史、触发增量压缩并组装模型上下文
AgentTurn turn = runner.run(agent, conversationId, new UserMessage("继续处理"));
```

`AgentContextCompressionStateStore` 只需要实现 `load` 和带 `expectedVersion` 的 CAS `save`。首次保存使用版本 `0`；
协调器成功压缩后会推进 `version`、`compressionVersion`、`coveredUntilMessageId`、覆盖 Token 数和 Turn 数。
业务侧应把这个状态和会话放在同一事务边界内，或使用数据库/Redis 的乐观锁，避免两个请求同时摘要而互相覆盖。
每次会话请求都会读取状态，但只有 `AgentContextCompressionTrigger` 返回 `true` 且存在新增消息时才调用摘要器和保存状态；
因此达到一次阈值后，后续请求不会每轮重复调用摘要模型，直到新增历史再次满足触发条件。配置了增量策略后，
Runner 会自动执行协调器，业务侧不需要手工调用 `compress(...)` 或传递 `getModelMessages()`。

传给协调器的可压缩历史必须是该会话对应范围内完整、按时间升序排列的消息列表。如果状态中的
`coveredUntilMessageId` 不在列表中，协调器会抛出异常而不是静默从头重复摘要；这通常表示分页不完整、消息被错误删除或状态与会话不一致。
协调器只生成模型调用视图，不修改 `ChatMemory`，CAS 冲突也不会覆盖已有摘要。

框架不强制业务侧采用某一种数据库，但 Store 模块提供 JDBC 和 Redis 实现：

```java
JdbcAgentStoreConfig jdbc = JdbcAgentStoreConfig.builder(dataSource)
    .tablePrefix("app_agent_")
    .build();
jdbc.schema().initialize();
AgentContextCompressionStateStore compressionStore = jdbc.compressionStateStore();
```

Redis 使用同样的 CAS 语义，适合多实例服务共享状态：

```java
RedisAgentStoreConfig redis = RedisAgentStoreConfig.builder("redis://127.0.0.1:6379")
    .keyPrefix("app:agent:")
    .build();
AgentContextCompressionStateStore compressionStore = redis.compressionStateStore();
```

JDBC 实现需要在启动时执行 `schema().initialize()`，或使用等价的数据库迁移脚本；Redis 实现不需要建表。
两种实现都使用 `FastjsonAgentStoreSerializer` 保存消息多态类型，也都支持通过配置替换为业务自定义序列化器。
应用负责关闭自己创建的 Redis 配置对象；JDBC `DataSource` 的生命周期仍由应用管理。

窗口始终保证模型消息起点是 `UserMessage`（如果配置了系统指令，则系统消息位于最前面）。
`AgentActionMessage`、`AgentFormMessage` 等 `modelVisible=false` 消息不会发送给模型。

### 已完成工具 Turn 的归一化

开启 `compactCompletedToolTurns` 后，较早且已经完成的 Turn：

```text
UserMessage
AiMessage(tool_calls)
ToolMessage
AiMessage(最终回复)
```

在模型上下文中会变成：

```text
UserMessage
AiMessage(最终回复)
```

这只改变模型 Prompt，不删除 `ChatMemory`、Snapshot 或工具审计记录。当前 Turn、挂起 Turn、失败 Turn、
取消 Turn 和没有最终正文的工具 Turn 会保留完整协议。

## 业务侧语义摘要

```java
List<Message> history = loadModelMessagesByWindow(persistedMemory);
List<Message> inputHistory = history;
if (history.size() > 40) {
    List<Message> older = history.subList(0, history.size() - 12);
    List<Message> recent = history.subList(history.size() - 12, history.size());
    String summary = summaryModel.summarize(older);
    inputHistory = new ArrayList<>();
    inputHistory.add(new UserMessage("Conversation summary:\n" + summary));
    inputHistory.addAll(recent);
}

AgentTurn turn = runner.run(agent, inputHistory, new UserMessage("继续处理"));
```

当前框架内置的是 Turn 边界和工具协议归一化，不会自动调用摘要模型。需要扫描很长的业务历史做语义摘要时，`loadModelMessagesByWindow` 应循环调用
`getMessages(offset, pageSize)`，边读取边摘要或写入临时存储，不要用 `Integer.MAX_VALUE` 构造全量 List。

摘要逻辑只读取业务历史并构造传给 Runner 的新列表，不应对数据库型 `ChatMemory` 调用 `clear()`。Runner 会复制传入的消息，因此也不会直接修改 `persistedMemory`。业务实现还应避免从一组 ToolCall/ToolMessage 中间截断，并在摘要失败时继续保留原历史。

自定义摘要提示应要求保留事实、业务 ID、未完成事项、审批结果和用户约束，不应把不可信工具输出提升为系统指令。

## 大型工具结果设计

Framework 不根据结果大小改写 ToolMessage，也不保存被替换的原始内容。Tool 应只返回模型完成下一步决策所需的数据，并根据业务语义选择以下方式控制结果规模：

- 查询类 Tool 提供分页、游标、过滤、字段选择和条数上限。
- 搜索或分析类 Tool 返回摘要与关键条目，并提供按 ID 获取详情的配套 Tool。
- 日志、报表和导出类 Tool 把完整内容保存到业务存储，只返回业务文件 ID、下载地址或状态。
- 数据规模不可预知时，返回 `hasMore`、`nextCursor`、截断原因等明确协议字段。

这些约束属于 Tool 契约，因为只有 Tool 和业务系统知道哪些内容可以截断、如何继续读取以及怎样鉴权。Runner 只负责保存 Tool 实际返回的协议消息。

## 多模态消息

使用结构化 `UserMessage` 传入图片、音频、文件等内容。Turn 创建时复制消息，并由 Snapshot 保留协议数据。实际模型是否支持对应模态取决于具体 `ChatModel`；持久化 Store 还需考虑二进制内容大小，通常应把大文件保存到对象存储，在消息中保留受控引用。

## 生产建议

- 同时限制消息数量、Tool 单次返回规模与模型 Token 预算。
- 业务侧摘要失败时保留原历史，不能清空或静默丢弃消息。
- 对业务文件引用实施租户隔离、有效期和访问授权。
- 监控 Snapshot 大小以及各 Tool 的返回大小、截断率和分页次数。
- 恢复后仍需使用的业务标识应保存为可序列化 metadata；密钥、连接和服务对象不要写入 Snapshot。

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
    .compactCompletedToolTurns(true)
    .compressionKeepRecentTurns(2)
    .contextCompressor(messages -> summarizeForModel(messages))
    .build();
```

默认最多附加最近 10 个完整 Turn 和 100 条消息。`maxAttachedTurns` 是主要的语义窗口，
`maxAttachedMessages` 是安全上限；框架不会从 ToolCall/ToolMessage 中间硬截断。单个当前 Turn
即使超过消息上限，也会保留完整协议，避免模型收到孤立的 ToolMessage 或未闭合 ToolCall。

`compactCompletedToolTurns` 只控制较早、已经完成且包含工具调用的 Turn 是否按规则归一化。它不表示所有历史 Turn 都会被压缩。`compressionKeepRecentTurns`（默认 2）会保护最近的若干完整 Turn，这些 Turn 保留原始 ToolCall、ToolMessage 和最终 AiMessage，不参与规则压缩或语义压缩；当前 Turn 始终属于保护范围。

`contextCompressor` 是可选的业务语义压缩器，接收较早且允许压缩的模型可见消息，返回要放入本次模型 Prompt 的消息。它只影响模型上下文，不修改 ChatMemory、Turn 或 Snapshot。返回结果必须以 `UserMessage` 开始，不能包含 UI 消息，并保持每个 `ToolMessage.toolCallId` 与前面 AiMessage 中 ToolCall ID 匹配；未配置时不会调用摘要模型，只执行规则归一化。

框架提供了几个无需额外模型调用的策略：`AgentContextCompressors.identity()` 原样复制消息，
`compactCompletedTurns()` 每轮只保留用户问题和最终 AI 回复，`textExcerpt(maxCharacters)` 提取历史文本为
单条摘要用户消息，`chain(...)` 可组合多个策略。生产环境通常应使用业务侧摘要模型实现
`AgentContextCompressor`，并在摘要失败时保留原始历史。

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

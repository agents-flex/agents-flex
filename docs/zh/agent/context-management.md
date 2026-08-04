---
title: 上下文管理
description: 管理业务 ChatMemory、消息窗口、摘要、多模态内容和工具结果边界。
---

# 上下文管理

## 概述

Agent 的上下文同时面对两个目标：保留足够历史以正确决策，又避免消息和工具结果无限增长。模型读取窗口使用 Agent 的普通参数 `maxAttachedMessages`；需要重写持久化历史时使用 `AgentContextManager`。工具返回内容由 Tool 自身控制，Runner 会把实际结果原样写入运行历史。

## 消息的三个层次

- 业务 `ChatMemory`：由应用维护，跨多轮 Run 保存历史。
- `AgentRun` 的 `MemoryPrompt`：本次任务的协议消息和系统指令。
- 模型调用 Prompt：依据 `maxAttachedMessages` 从 Run Prompt 生成的当前视图。

窗口策略只影响一次模型调用视图；Context Manager 的修改会进入 Run 与 Snapshot。

## 业务会话管理

```java
List<Message> history = persistedMemory.getMessages(Integer.MAX_VALUE);
AgentRun run = runner.run(agent, history, new UserMessage("继续处理"));
```

应用负责持久化 conversationId、`ChatMemory` 和当前未结束的 runId。Run 完成后使用 `getConversationHistory()` 更新 Memory；同一业务会话不应并发开始两轮；阻塞时必须按已保存的 runId 恢复原 Run，完成后再开始下一轮。Runner 不会直接修改业务 ChatMemory。

## 模型读取窗口

```java
Agent agent = Agent.builder("support-agent")
    .chatModel(chatModel)
    .maxAttachedMessages(40)
    .build();
```

默认最多附加最近 100 条历史消息；可使用 `Integer.MAX_VALUE` 读取全部历史。该参数只影响单次模型请求视图，不释放 Snapshot 存储空间。窗口按协议消息计数，不按自然语言轮次计数，包含 ToolCall 时应预留足够空间；需要永久压缩时使用 Context Manager。

## 消息数量摘要

```java
AgentConversationSummarizer summarizer = messages ->
    summaryModel.summarize(messages);

AgentContextManager manager = new MessageCountAgentContextManager(
    40,   // 超过 40 条触发
    12,   // 始终保留最近 12 条
    summarizer);

Agent agent = Agent.builder("support-agent")
    .chatModel(chatModel)
    .contextManager(manager)
    .build();
```

管理器在模型调用前运行，把较早历史总结为带 `agent.context.summary` 元数据的 `UserMessage`。它会尽量避免从一组 ToolCall/ToolMessage 中间截断。摘要结果为空或摘要器失败时，管理器不会破坏原历史。

自定义摘要提示应要求保留事实、业务 ID、未完成事项、审批结果和用户约束，不应把不可信工具输出提升为系统指令。

## 自定义 Context Manager

```java
public final class DomainContextManager implements AgentContextManager {
    @Override
    public AgentContextUpdate prepare(AgentRun run) {
        // 只通过受控消息 API整理可持久化历史
        return AgentContextUpdate.unchanged();
    }
}
```

实现必须幂等：同一 Snapshot 因重试再次执行时，不应重复插入摘要。返回 `changed=true` 后，Runner 会立即保存 Snapshot 并发布 `CONTEXT_COMPACTED`。

## 大型工具结果设计

Framework 不根据结果大小改写 ToolMessage，也不保存被替换的原始内容。Tool 应只返回模型完成下一步决策所需的数据，并根据业务语义选择以下方式控制结果规模：

- 查询类 Tool 提供分页、游标、过滤、字段选择和条数上限。
- 搜索或分析类 Tool 返回摘要与关键条目，并提供按 ID 获取详情的配套 Tool。
- 日志、报表和导出类 Tool 把完整内容保存到业务存储，只返回业务文件 ID、下载地址或状态。
- 数据规模不可预知时，返回 `hasMore`、`nextCursor`、截断原因等明确协议字段。

这些约束属于 Tool 契约，因为只有 Tool 和业务系统知道哪些内容可以截断、如何继续读取以及怎样鉴权。Runner 只负责保存 Tool 实际返回的协议消息。

## 多模态消息

使用结构化 `UserMessage` 传入图片、音频、文件等内容。Run 创建时复制消息，并由 Snapshot 保留协议数据。实际模型是否支持对应模态取决于具体 `ChatModel`；持久化 Store 还需考虑二进制内容大小，通常应把大文件保存到对象存储，在消息中保留受控引用。

## 生产建议

- 同时限制消息数量、Tool 单次返回规模与模型 Token 预算。
- 摘要模型失败时保留原历史，不能静默丢消息。
- 对业务文件引用实施租户隔离、有效期和访问授权。
- 监控 Snapshot 大小以及各 Tool 的返回大小、截断率和分页次数。
- 恢复后仍需使用的业务标识应保存为可序列化 metadata；密钥、连接和服务对象不要写入 Snapshot。

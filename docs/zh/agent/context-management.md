---
title: 上下文管理
description: 管理业务 ChatMemory、消息窗口、摘要、多模态内容和工具结果边界。
---

# 上下文管理

## 概述

Agent 的上下文同时面对两个目标：保留足够历史以正确决策，又避免消息和工具结果无限增长。Framework 使用 `maxAttachedMessages` 限制单次模型请求读取的历史，但不会清空或重写 `ChatMemory`。需要摘要或永久整理历史时，由业务系统在调用 Runner 前完成。工具返回内容由 Tool 自身控制，Runner 会把实际结果原样写入运行历史。

## 消息的三个层次

- 业务 `ChatMemory`：由应用维护，跨多轮 Turn 保存历史。
- `AgentTurn` 的 `MemoryPrompt`：本次任务的协议消息和系统指令。
- 模型调用 Prompt：依据 `maxAttachedMessages` 从 Turn Prompt 生成的当前视图。

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

应用负责持久化 conversationId、`ChatMemory` 和当前未结束的 turnId。同一业务会话不应并发开始两轮；
阻塞时必须按已保存的 turnId 恢复原 Turn，完成后再开始下一轮。`ChatMemoryProvider` 只定位 Memory，
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
    .maxAttachedMessages(40)
    .build();
```

默认最多附加最近 100 条历史消息；可使用 `Integer.MAX_VALUE` 读取全部历史。该参数只影响单次模型请求视图，不释放 Snapshot 存储空间。窗口按协议消息计数，不按自然语言轮次计数，包含 ToolCall 时应预留足够空间。

## 业务侧摘要

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

需要扫描很长的业务历史做摘要时，`loadModelMessagesByWindow` 应循环调用
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

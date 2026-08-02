---
title: 上下文管理
description: 管理 Conversation、消息窗口、摘要、多模态内容和大型工具结果。
---

# 上下文管理

## 概述

Agent 的上下文同时面对两个目标：保留足够历史以正确决策，又避免消息和工具结果无限增长。该模块把“模型本次看到哪些消息”“是否重写持久化历史”“大结果保存在哪里”拆成三个扩展点：`AgentContextPolicy`、`AgentContextManager` 和 `ToolResultOffloadPolicy`。

## 消息的三个层次

- `AgentConversation`：跨多轮 Run 共享的 `ChatMemory`。
- `AgentRun` 的 `MemoryPrompt`：本次任务的协议消息和系统指令。
- 模型调用 Prompt：依据 Context Policy 从 Run Prompt 生成的当前视图。

窗口策略只影响一次模型调用视图；Context Manager 的修改会进入 Run 与 Checkpoint。

## Conversation 管理

```java
AgentConversation conversation = AgentConversation.of(
    "session-1", agent, persistedMemory);

AgentRun run = runner.run(conversation, new UserMessage("继续处理"));
```

应用负责持久化 Conversation 的消息与 `activeRunId`。同一 Conversation 不应并发开始两轮；阻塞时必须恢复 active Run，完成后再开始下一轮。

## 上下文读取策略

默认 `AgentContextPolicy.defaults()` 使用完整消息。需要限制模型视图时，可使用窗口策略，以当前版本提供的工厂或实现为准。窗口必须保留系统指令，并避免只保留 ToolMessage 而丢失其对应 AiMessage/ToolCall。

Context Policy 适合临时裁剪，不会释放快照存储空间；需要永久压缩时使用 Context Manager。

## 消息数量摘要

```java
AgentConversationSummarizer summarizer = (messages, context) ->
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
    public AgentContextUpdate prepare(
        AgentRun run, AgentInvocationContext invocationContext) {
        // 只通过受控消息 API整理可持久化历史
        return AgentContextUpdate.unchanged();
    }
}
```

实现必须幂等：同一 Checkpoint 因重试再次执行时，不应重复插入摘要。返回 `changed=true` 后，Runner 会立即保存 Checkpoint 并发布 `CONTEXT_COMPACTED`。

## 大型工具结果外置

```java
Agent agent = Agent.builder("research-agent")
    .chatModel(chatModel)
    .toolResultOffloadPolicy(ToolResultOffloadPolicy.largerThan(20_000))
    .build();

AgentRunner runner = AgentRunner.builder()
    .artifactStore(persistentArtifactStore)
    .build();
```

超过阈值的工具结果会保存到 `AgentArtifactStore`，Prompt 中替换为包含 `artifactId`、media type、size 和 checksum 的 JSON 引用。原始内容可用 `artifactStore.load(artifactId)` 读取。

这能降低 Prompt 与 Checkpoint 体积，但模型只看到引用摘要。如果后续推理需要正文，应提供按需读取 Artifact 的工具，或让卸载策略只处理无需再次推理的大内容。

## 多模态消息

使用结构化 `UserMessage` 传入图片、音频、文件等内容。Run 创建时复制消息，并由 Snapshot 保留协议数据。实际模型是否支持对应模态取决于具体 `ChatModel`；持久化 Store 还需考虑二进制内容大小，通常应把大文件保存到对象存储，在消息中保留受控引用。

## 瞬时调用上下文

`AgentInvocationContext` 可携带 tenantId、userId、requestId、sessionId 和运行期 attributes。它不持久化，用于鉴权、工具服务定位和摘要器上下文。Worker 恢复时通过 `AgentInvocationContextProvider` 重建，避免把密钥和服务对象写进 Checkpoint。

## 生产建议

- 同时限制消息数量、工具结果大小与模型 Token 预算。
- 摘要模型失败时保留原历史，不能静默丢消息。
- Artifact 与 Run 设置一致的租户隔离、保留期和删除策略。
- 对 Checkpoint 和 Artifact 分别监控大小、增长率与读取失败。

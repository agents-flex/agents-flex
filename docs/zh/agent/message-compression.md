---
title: 消息压缩
description: 从上下文窗口、规则归一化到可持久化的增量语义摘要，逐步构建可靠的消息压缩方案。
---

# 消息压缩

## 概述

对话越长，发送给模型的消息越多，Token 成本、响应延迟和触达模型上下文上限的风险都会增加。Agent 的消息压缩解决的是“模型本次需要看到什么”，而不是“业务历史应该删除什么”。

压缩始终只生成模型调用视图，原始 `ChatMemory`、`AgentTurn`、ToolCall、ToolMessage 和审计记录不会被清空或改写。

压缩相关扩展类型统一位于 `com.agentsflex.agent.compression` 包；Agent 侧只需要配置一个
`compressionPolicy`，不需要在运行时自行组装压缩流程。

消息压缩通常分为三层：

| 层次 | 机制 | 是否调用模型 | 适用场景 |
| --- | --- | --- | --- |
| 窗口限制 | `maxAttachedTurns`、`maxAttachedMessages` | 否 | 控制每次请求的最大历史范围 |
| 规则压缩 | `compactCompletedToolTurns` | 否 | 删除较早已完成工具 Turn 的中间协议消息 |
| 语义压缩 | `AgentContextCompressor` | 可选 | 把较长历史提炼成事实、约束和决定 |

当压缩条件不是固定消息数量，还可以使用 `AgentContextCompressionCondition` 和业务侧 Store，按 Token、Turn、工具结果大小、时间或租户配额做增量压缩。

## 快速开始

### 只限制模型窗口

这是最简单、成本最低的方式。它不改变历史，也不会调用摘要模型：

```java
Agent agent = Agent.builder("support-agent")
    .chatModel(chatModel)
    .maxAttachedTurns(6)
    .maxAttachedMessages(40)
    .build();
```

框架按完整 Turn 选择消息，不会从 ToolCall 和 ToolMessage 中间截断。当前 Turn 和必要的协议消息会优先保持完整。

### 归一化已完成工具 Turn

对于已经完成的旧工具 Turn，可以只保留用户问题和最终 AI 回复：

```java
Agent agent = Agent.builder("support-agent")
    .chatModel(chatModel)
    .compressionPolicy(AgentContextCompressionPolicy.builder()
        .compactCompletedToolTurns(true)
        .keepRecentTurns(2)
        .build())
    .build();
```

例如：

```text
UserMessage
AiMessage(tool_calls)
ToolMessage
AiMessage(最终回复)
```

在模型上下文中变为：

```text
UserMessage
AiMessage(最终回复)
```

最近保护 Turn、当前 Turn、挂起 Turn、失败 Turn 和取消 Turn 不会被错误地删除。原始消息仍保留在业务 `ChatMemory` 中。

### 使用摘要模型

需要保留业务事实和长期约束时，配置语义压缩器：

```java
AgentContextCompressor compressor = AgentContextCompressors.model(
    summaryModel,
    "请用中文总结历史事实，保留业务 ID、时间、用户约束、审批结果和未完成事项，不要编造信息。");

Agent agent = Agent.builder("support-agent")
    .chatModel(chatModel)
    .compressionPolicy(AgentContextCompressionPolicy.builder()
        .compressor(compressor)
        .keepRecentTurns(3)
        .build())
    .build();
```

摘要模型的结果只用于当前模型请求，不会回写 `ChatMemory`。摘要失败时应保留原始历史并记录错误，不要直接清空业务数据库。

## 为什么需要增量压缩

如果每次达到上下文上限后都把全部历史重新交给摘要模型，会出现两个问题：

1. 相同的早期消息被重复摘要，增加模型调用和成本。
2. 并发请求可能同时生成摘要，后写入的结果覆盖先写入的进度。

增量压缩把历史划分为连续区间：

```text
消息 1~100   -> 第一次压缩，保存游标 message-100
消息 101~200 -> 第二次压缩，读取旧摘要，只处理新增区间
消息 201~300 -> 第三次压缩，继续推进游标
```

`AgentContextCompressionState` 保存摘要和覆盖游标；`AgentContextCompressionCondition` 决定本次是否达到条件；策略内部的协调器负责组合旧摘要与新增消息、调用压缩器并通过 CAS 保存新状态。

## 按 Token 或业务条件触发

```java
Agent agent = Agent.builder("support-agent")
    .chatModel(chatModel)
    .compressionPolicy(AgentContextCompressionPolicy.incremental(
        compressionStateStore,
        input -> input.getEstimatedPendingTokens() >= 100_000,
        AgentContextCompressors.model(
            summaryModel,
            "保留事实、实体 ID、约束、审批结果和未完成事项"),
        messages -> tokenCounter.estimate(messages)))
    .build();

AgentTurn turn = runner.run(agent, conversationId, new UserMessage("继续处理"));
```

条件通过 `AgentContextCompressionInput` 收到以下只读信息：

- 上次游标之后的 `input.getPendingMessages()`
- 已持久化的 `input.getSummaryMessages()`
- 新增消息的估算 Token 数 `input.getEstimatedPendingTokens()`
- 新增消息中的 Turn 数 `input.getPendingTurnCount()`
- 当前完整压缩状态 `input.getState()`

因此可以表达更复杂的业务规则：

```java
input -> input.getEstimatedPendingTokens() >= 80_000
    || input.getPendingTurnCount() >= 20
```

也可以直接使用内置条件并组合多个条件：

```java
AgentContextCompressionCondition condition = AgentContextCompressionConditions.anyOf(
    AgentContextCompressionConditions.pendingTokensAtLeast(80_000),
    AgentContextCompressionConditions.pendingTurnsAtLeast(20),
    AgentContextCompressionConditions.pendingMessagesAtLeast(100));
```

只有存在新增消息且触发器返回 `true` 时，才调用压缩器并保存状态。达到一次阈值后，后续没有足够新增内容的请求不会重复调用摘要模型。
配置增量 `compressionPolicy` 后，这些步骤由 Runner 自动完成。

协调器以 `AgentContextCompressionResult` 返回本轮是否完成压缩、最新状态以及最终模型消息视图；业务侧通常不需要直接处理该结果，Runner 会自动将其绑定到当前 Turn 和模型请求。

## 状态持久化

状态 Store 是业务侧可替换的最小接口：

```java
public interface AgentContextCompressionStateStore {
    AgentContextCompressionState load(String conversationId);

    boolean save(String conversationId,
                 AgentContextCompressionState state,
                 long expectedVersion);
}
```

首次保存使用 `expectedVersion = 0`。保存成功后，状态中的 `version` 会推进为新版本。CAS 返回 `false` 表示另一个请求已经先保存了状态，业务侧应重新加载历史和状态后重试，而不是覆盖对方摘要。

### JDBC

```java
JdbcAgentStoreConfig config = JdbcAgentStoreConfig.builder(dataSource)
    .tablePrefix("app_agent_")
    .build();
config.schema().initialize();
AgentContextCompressionStateStore store = config.compressionStateStore();
```

JDBC 实现创建 `app_agent_compression_states` 表，保存会话 ID、版本和序列化后的状态正文。生产环境也可以把等价建表语句放入 Flyway、Liquibase 等迁移工具。

### Redis

```java
RedisAgentStoreConfig config = RedisAgentStoreConfig
    .builder("redis://127.0.0.1:6379")
    .keyPrefix("app:agent:")
    .build();
AgentContextCompressionStateStore store = config.compressionStateStore();
```

Redis 实现使用 Hash 保存状态，并通过 Lua 脚本完成版本比较和写入，适合多实例服务共享。由应用创建的 `RedisAgentStoreConfig` 使用完后应负责关闭；外部传入的 Redis 客户端仍由应用管理。

## 自定义压缩策略

`AgentContextCompressor` 只负责“如何压缩”，可以完全不依赖大模型：

```java
AgentContextCompressor compressor = AgentContextCompressors.chain(
    AgentContextCompressors.compactCompletedTurns(),
    AgentContextCompressors.textExcerpt(8_000));
```

常见扩展方式：

- `identity()`：只复制消息，用于关闭压缩或调试。
- `compactCompletedTurns()`：每个已完成 Turn 只保留 UserMessage 和最终 AiMessage。
- `textExcerpt(maxCharacters)`：生成简单文本摘录，适合低成本兜底。
- `model(...)`：将整段历史总结为合法的模型消息。
- `perMessageModel(...)`：为每条 User/AI 消息生成摘要，保留消息角色和 ID。
- 自定义实现：按领域提取订单、权限、审批、时间和未完成事项等结构化事实。

摘要消息应携带来源和压缩版本 metadata，避免同一摘要被二次处理。包含未完成 ToolCall 的 Turn 不应被普通文本摘要替换，必须保留完整 ToolCall/ToolMessage 协议。

## 边界与故障处理

### 压缩游标不存在

协调器要求传入的可压缩历史包含 `coveredUntilMessageId`。如果分页遗漏了该消息、历史被错误删除或状态与会话不一致，会抛出异常，而不是静默从头重复摘要。

### CAS 冲突

CAS 冲突说明同一会话存在并发压缩。应重新读取最新状态和完整历史，再执行一次触发判断；不要直接使用旧的 `modelMessages`。

### 摘要失败

压缩器返回 `null` 或空列表会被视为失败。业务侧应保留旧状态，记录失败原因，并可以暂时使用窗口限制或规则压缩继续服务。

### 原始历史与模型视图

压缩不会调用 `ChatMemory.clear()`，也不会删除事件、Snapshot 或 ToolMessage。页面历史继续读取业务 `ChatMemory`；只有发给模型的 Prompt 使用压缩后的消息列表。

## 生产建议

- 将压缩状态和会话消息放在同一租户、同一会话的持久化边界内。
- 为摘要模型设置独立 Token 预算和超时，避免压缩阻塞正常对话。
- 监控压缩次数、覆盖消息数、Token 节省量、摘要失败率和 CAS 冲突率。
- 保留最近完整 Turn，尤其是仍可能恢复的审批、表单和工具调用 Turn。
- 对摘要内容做版本化和可追踪记录，必要时支持重新生成或回滚。

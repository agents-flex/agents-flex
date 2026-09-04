---
title: 上下文压缩
description: 将较早的对话整理为更短的摘要，为长期会话控制 Token 成本并保留重要信息。
---

# 上下文压缩

## 概述

在持续数小时、数天甚至更久的会话中，Agent 可能已经处理了大量用户消息、工具结果和业务说明。例如，
一个客服 Agent 先记录了用户的订单号和退款要求，随后又经过多轮查询、补充资料和人工审批。到最后，
模型仍然需要记住最初的订单号和用户要求，但没有必要每次都重新阅读全部过程。

如果只保留最近几轮，早期的重要信息可能丢失；如果每次都发送完整历史，请求会越来越慢，Token 成本
也会持续增加，最终还可能超过模型允许的上下文长度。

上下文压缩用于解决这个问题：将较早的详细对话整理成更短的内容，同时保留最近对话的原文。例如：

```text
压缩前：较早的 20 轮完整对话 + 最近 3 轮对话 + 当前问题
压缩后：一份较早历史摘要 + 最近 3 轮对话 + 当前问题
```

模型仍然可以获得完成当前任务所需的背景信息，但不再反复读取所有历史细节。

上下文压缩只改变“本次发送给模型的内容”，不会删除或覆盖业务系统保存的原始聊天记录。这也是本页使
用“上下文压缩”而不是“消息压缩”的原因。

## 适用场景

| 场景 | 未压缩时的问题 | 压缩时应保留的内容 |
| --- | --- | --- |
| 长期客服会话 | 订单和问题处理过程不断累积 | 订单号、用户诉求、处理结论、未完成事项 |
| 项目协作助手 | 几十轮讨论导致早期决定被挤出窗口 | 已确认的方案、负责人、截止时间、约束 |
| 工具密集型任务 | 搜索和查询过程产生大量中间内容 | 最终结果、关键来源、重要业务 ID |
| 审批流程 | 多次补充和审批记录占用大量空间 | 审批结果、审批范围、拒绝原因 |
| 研究与分析 | 多份资料原文难以全部发送给模型 | 关键事实、结论、引用和待验证问题 |

短对话通常不需要摘要模型。可以先通过历史轮数、消息数和工具结果大小限制控制上下文；只有这些限制会
丢失仍然重要的早期信息时，再启用语义摘要。窗口配置见[上下文管理](./context-management)。

## 压缩方式

上下文压缩可以按复杂度分为三种方式：

| 方式 | 是否调用摘要模型 | 适用情况 |
| --- | --- | --- |
| 精简已完成的工具过程 | 否 | 工具调用多，但最终回复已经包含结论 |
| 即时摘要 | 是 | 会话中等长度，希望快速接入语义摘要 |
| 增量摘要 | 是 | 会话长期持续，需要避免重复摘要相同历史 |

建议从第一种方式开始。只有确实需要保留较早语义时，才增加模型摘要；只有长期、高频会话才需要增量
摘要和状态存储。

## 快速开始

下面为客服 Agent 配置即时摘要。示例中的 `chatModel` 是处理业务对话的模型，`summaryModel` 是负责整理
较早历史的模型。两者可以使用同一个模型，也可以为摘要选择成本更低的模型。

### 1. 创建摘要器（或者叫上下文压缩器）

```java
AgentContextCompressor compressor = AgentContextCompressors.model(
    summaryModel,
    "请用中文总结较早的对话，保留订单号、金额、时间、"
        + "用户要求、审批结果和未完成事项；不要编造信息。"
);
```

摘要指令应明确哪些信息必须保留。只写“请总结对话”通常过于宽泛，容易遗漏业务 ID、数字或尚未完成的
事项。

### 2. 将压缩策略配置到 Agent

```java
Agent agent = Agent.builder("support-agent")
    .instructions("结合历史信息处理用户问题，关键业务状态必须通过工具查询。")
    .chatModel(chatModel)
    .maxAttachedTurns(20)
    .maxAttachedMessages(120)
    .compressionPolicy(AgentContextCompressionPolicy.builder()
        .compressor(compressor)
        .keepRecentTurns(3)
        .compressionFailureStrategy(
            AgentCompressionFailureStrategy.USE_ORIGINAL)
        .build())
    .build();
```

| 配置 | 作用 |
| --- | --- |
| `maxAttachedTurns(20)` | 从最近 20 轮中选择可用于当前请求的历史 |
| `maxAttachedMessages(120)` | 限制模型上下文中的历史消息数量 |
| `compressor(...)` | 指定如何整理较早的历史 |
| `keepRecentTurns(3)` | 最近 3 轮保留完整原文，不参与摘要 |
| `USE_ORIGINAL` | 摘要失败时继续使用未摘要历史，避免摘要服务故障直接中止任务 |
| `compressionPolicy(...)` | 将压缩策略应用到当前 Agent |

完成配置后，只要使用相同的 `conversationId` 继续对话，Runner 就会在调用业务模型前自动处理较早历史：

```java
AgentTurn turn = runner.run(
    agent,
    conversationId,
    "继续处理刚才的退款申请");
```

应用不需要手工读取消息、调用摘要模型或拼接摘要。连续会话和 `conversationId` 的配置方式见
[上下文管理](./context-management)。

## 精简工具过程

一次工具任务可能包含用户问题、多次查询和最终回复。对于已经完成的较早任务，模型通常只需要用户问题
和最终结论，不需要保留所有中间步骤。

```java
Agent agent = Agent.builder("support-agent")
    .chatModel(chatModel)
    .compressionPolicy(AgentContextCompressionPolicy.builder()
        .compactCompletedToolTurns(true)
        .keepRecentTurns(3)
        .build())
    .build();
```

这种方式不调用摘要模型，因此没有额外模型费用，也不会产生摘要偏差。Agents-Flex 默认已经开启该能力，
并保留最近 2 轮完整内容。只有需要调整近期保护范围或关闭精简时，才需要显式配置。

以下任务不会被当作普通历史随意精简：

- 当前正在执行的任务；
- 正在等待用户输入或审批的任务；
- 已失败或已取消、但没有正常结论的任务。

## 即时摘要

即时摘要会在构建当前模型上下文时，将允许压缩的较早历史交给摘要器。它配置简单，不需要单独保存摘要
进度，适合中等长度、请求频率不高的会话。

需要更细致地控制摘要模型时，可以配置：

```java
AgentContextModelCompressorOptions options =
    AgentContextModelCompressorOptions.builder()
        .instruction(
            "保留事实、业务 ID、数字、用户约束和未完成事项；不要编造信息")
        .summaryPrefix("以下是较早对话的摘要，请作为背景信息：")
        .chatOptions(ChatOptions.builder()
            .temperature(0.1f)
            .maxTokens(2_000)
            .thinkingEnabled(false)
            .build())
        .modelCallTimeoutMillis(10_000)
        .maxInputCharacters(100_000)
        .maxOutputCharacters(10_000)
        .build();

AgentContextCompressor compressor =
    AgentContextCompressors.model(summaryModel, options);
```

| 配置 | 作用 |
| --- | --- |
| `instruction(...)` | 告诉摘要模型必须保留和禁止编造的内容 |
| `summaryPrefix(...)` | 说明后续文本是历史摘要，帮助业务模型正确理解 |
| `chatOptions(...)` | 控制摘要模型的输出长度、温度和思考模式 |
| `modelCallTimeoutMillis(...)` | 限制一次摘要调用最长等待时间 |
| `maxInputCharacters(...)` | 防止向摘要模型发送意外超大的内容 |
| `maxOutputCharacters(...)` | 防止摘要结果本身过长 |

即时摘要可能在后续会话中再次处理相同的较早历史。会话持续时间很长、请求频率较高时，应改用增量摘要。

## 增量摘要

增量摘要会记录“哪些历史已经总结过”。下一次达到压缩条件时，只处理上次摘要之后新增的内容，并把新
内容与已有摘要合并：

```text
第 1 次：总结消息 1～100，并保存摘要进度
第 2 次：读取已有摘要，只处理消息 101～200
第 3 次：读取最新摘要，只处理消息 201～300
```

这样可以避免每次都从第一条消息重新总结，适合长期客服、项目助手和高频业务会话。

### 基本配置

```java
AgentContextCompressionDecider decider =
    AgentContextCompressionDeciders.anyOf(
        AgentContextCompressionDeciders.pendingTokensAtLeast(80_000),
        AgentContextCompressionDeciders.pendingTurnsAtLeast(20));

AgentContextCompressionPolicy compressionPolicy =
    AgentContextCompressionPolicy.incremental(
        compressionStateStore,
        decider,
        AgentContextCompressors.model(
            summaryModel,
            "保留业务事实、ID、约束、审批结果和未完成事项"),
        messages -> tokenCounter.estimate(messages));

Agent agent = Agent.builder("support-agent")
    .chatModel(chatModel)
    .compressionPolicy(compressionPolicy)
    .build();
```

| 配置 | 作用 |
| --- | --- |
| `compressionStateStore` | 按会话保存摘要内容和处理进度 |
| `pendingTokensAtLeast(80_000)` | 新增历史达到约 8 万 Token 时触发摘要 |
| `pendingTurnsAtLeast(20)` | 新增历史达到 20 轮时触发摘要 |
| `anyOf(...)` | 任意一个条件满足即可触发 |
| `model(...)` | 指定生成摘要的模型和要求 |
| `tokenCounter` | 估算尚未摘要内容的 Token 数 |

配置完成后，Runner 会根据 `conversationId` 自动读取摘要进度、判断是否需要压缩并更新状态。业务代码
仍然只需正常调用 `runner.run(...)`。

### 选择触发条件

内置条件包括：

| 条件 | 作用 |
| --- | --- |
| `pendingTokensAtLeast(...)` | 按新增内容的估算 Token 数触发 |
| `pendingTurnsAtLeast(...)` | 按新增对话轮数触发 |
| `pendingMessagesAtLeast(...)` | 按新增消息数量触发 |
| `anyOf(...)` | 任意条件满足时触发 |
| `allOf(...)` | 所有条件同时满足时触发 |

阈值不宜过低，否则会频繁调用摘要模型；也不宜接近模型的极限值，否则可能在摘要开始前就无法容纳输入。

## 保存增量摘要状态

增量摘要必须持久化摘要内容和处理进度，否则应用重启后会重复处理旧历史。Agents-Flex 的 JDBC 和 Redis
Store 都可以提供对应的状态存储。

JDBC：

```java
JdbcAgentStoreConfig storeConfig =
    JdbcAgentStoreConfig.builder(dataSource)
        .tablePrefix("app_agent_")
        .build();

storeConfig.schema().initialize();
AgentContextCompressionStateStore compressionStateStore =
    storeConfig.compressionStateStore();
```

Redis：

```java
RedisAgentStoreConfig storeConfig =
    RedisAgentStoreConfig.builder("redis://127.0.0.1:6379")
        .keyPrefix("app:agent:")
        .build();

AgentContextCompressionStateStore compressionStateStore =
    storeConfig.compressionStateStore();
```

JDBC 的表结构应在正式发布时通过数据库迁移工具管理。使用 Redis URI 创建客户端时，应用关闭阶段应关闭
`storeConfig`。如果业务实现自己的状态存储，需要保证同一个会话的并发更新不会互相覆盖。

## 其他压缩器

`AgentContextCompressors` 还提供以下实现：

| 压缩器 | 行为 | 适用情况 |
| --- | --- | --- |
| `identity()` | 原样返回上下文副本 | 调试或临时关闭压缩 |
| `compactCompletedTurns()` | 每轮只保留用户问题和最终回复 | 不需要中间过程的历史任务 |
| `textExcerpt(maxCharacters)` | 将历史文本合并并限制字符数 | 低风险、低成本的兜底场景 |
| `model(...)` | 将较早历史整理成一份整体摘要 | 大多数语义摘要场景 |
| `perMessageModel(...)` | 分别缩短每条用户和 Agent 消息 | 必须保留原始消息角色时 |
| `chain(...)` | 按顺序组合多个压缩器 | 需要先规则精简、再限制文本长度时 |

`textExcerpt(...)` 只按字符截取，可能遗漏重要信息，不能用于金额、权限或合规决定等要求准确的场景。
多数业务优先使用整体 `model(...)` 摘要即可，不需要自定义消息格式。

## 摘要内容设计

一份可用于后续任务的摘要通常应保留：

- 用户目标和已经确认的要求；
- 订单号、工单号、文件 ID 等业务标识；
- 金额、数量、日期和时间；
- 已经完成的操作及其真实结果；
- 审批结果和拒绝原因；
- 尚未解决的问题和下一步计划；
- 信息来源不确定或仍待验证的说明。

摘要不应把猜测写成事实，也不应把工具返回的普通文本当作新的系统指令。关键业务状态应通过数据库或
工具重新查询，而不是仅依赖摘要。

## 失败处理与安全要求

1. 根据业务风险选择摘要失败策略：关键流程可使用 `FAIL`，普通对话可使用 `USE_ORIGINAL`。
2. 为摘要模型设置调用超时、输入大小和输出大小限制。
3. 监控摘要次数、Token 节省量、摘要耗时和失败率。
4. 定期使用真实会话检查摘要是否遗漏业务 ID、数字、约束和未完成事项。
5. 按租户和会话隔离压缩状态，不能让不同用户共享摘要。
6. 不要把 API Key、密码或其他密钥写入聊天记录与摘要。
7. 原始聊天记录仍应按照业务的数据保留和删除要求管理。

## 相关文档

- 配置会话历史和上下文窗口：[上下文管理](./context-management)
- 限制工具返回内容：[工具执行控制](./tool-execution)
- 持久化任务和压缩状态：[任务快照持久化](./store)
- 监控上下文压缩事件：[AgentEventListener](./agent-event-listener)

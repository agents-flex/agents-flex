---
title: 上下文管理
description: 让 Agent 在连续对话中保留必要信息，同时控制历史消息带来的成本、延迟和长度。
---

# 上下文管理

## 概述

大模型不会自动记住之前发生的事情。每次调用模型时，都需要把当前问题以及必要的历史信息一起发送给
它，模型才能理解“它”“刚才那个订单”“继续处理”等表达。

例如，在客服会话中，用户先说：

> 我的订单号是 O-1001，昨天显示已经发货。

随后又问：

> 它什么时候能到？

如果没有带上前一轮对话，模型就不知道“它”指的是哪个订单。但如果把几个月的所有聊天记录、工具
结果和文件内容都发送给模型，又会带来新的问题：请求更慢、Token 成本更高，并且可能超过模型允许的
上下文长度。

上下文管理解决的就是这个平衡问题：

- 保留当前任务真正需要的历史，让 Agent 能够连续理解用户意图；
- 限制每次发送给模型的历史范围，控制响应时间和 Token 消耗；
- 对较早内容进行精简或摘要，为近期对话留出空间；
- 控制大型工具结果和文件内容，避免无关信息占满上下文。

上下文管理只决定“这一次让模型看到哪些信息”，不会自动删除业务系统保存的原始聊天记录。

## 适用场景

| 场景 | 常见问题 | 建议做法 |
| --- | --- | --- |
| 连续客服对话 | 模型忘记订单号、产品或用户要求 | 使用固定会话 ID 保存历史 |
| 长时间业务助手 | 历史越来越长，请求逐渐变慢 | 限制历史轮数和消息数量 |
| 工具调用较多 | 查询结果和中间过程占用大量空间 | 精简已完成的工具过程 |
| 长期会话 | 早期约束仍然重要，但无法保留全部原文 | 使用摘要保留关键事实 |
| 文档、日志分析 | 单次工具结果可能非常大 | 分页、摘要或只返回文件引用 |
| 图片和文件输入 | 二进制内容增加存储和请求压力 | 文件单独存储，上下文保留受控引用 |

只有单轮问答、每次请求彼此独立的应用，通常不需要会话历史。只要用户会使用“继续”“上一个”“按刚才
的条件”等表达，就需要考虑上下文管理。

## 快速开始

下面创建一个可以连续对话的客服 Agent。示例中的 `chatModel` 表示已经创建好的大模型客户端。

### 1. 按会话保存聊天记录

```java
String conversationId = "customer-1001";
Map<String, ChatMemory> memories = new ConcurrentHashMap<>();
memories.put(
    conversationId,
    new DefaultChatMemory(conversationId));

ChatMemoryProvider memoryProvider = id -> memories.get(id);

AgentRunner runner = AgentRunner.builder()
    .chatMemoryProvider(memoryProvider)
    .build();
```

| 配置 | 作用 |
| --- | --- |
| `ChatMemory` | 保存一段会话中的用户消息和 Agent 回复 |
| `conversationId` | 标识一段连续会话，同一个会话应始终使用同一个 ID |
| `ChatMemoryProvider` | 根据会话 ID 找到对应的聊天记录 |
| `chatMemoryProvider(...)` | 将会话记录接入 `AgentRunner` |

业务系统负责创建会话和 `conversationId`，Provider 只负责查找已经存在的会话。找不到对应会话时，不应
静默创建另一份同名记录。

`DefaultChatMemory` 将消息保存在当前进程内，适合本地学习。应用重启后内容会丢失，生产环境应替换为
数据库或缓存实现。

### 2. 设置模型可以读取的历史范围

```java
Agent agent = Agent.builder("support-agent")
    .instructions(
        "你是订单客服。请结合当前会话理解用户的指代；"
            + "缺少必要信息时应明确询问，不得猜测订单状态。")
    .chatModel(chatModel)
    .maxAttachedTurns(6)
    .maxAttachedMessages(40)
    .build();
```

| 配置 | 作用 |
| --- | --- |
| `maxAttachedTurns(6)` | 每次调用模型时，最多附带最近 6 轮完整对话 |
| `maxAttachedMessages(40)` | 最多附带 40 条历史消息，防止某一轮包含过多工具结果 |

这里的“一轮”从一次用户输入开始，包括 Agent 为完成该请求产生的回复和工具结果。按完整轮次保留历史，
可以避免只留下结果、却丢失对应问题。

### 3. 使用同一个会话 ID 连续提问

```java
AgentTurn first = runner.run(
    agent,
    conversationId,
    "我的订单号是 O-1001，昨天显示已经发货。");

AgentTurn second = runner.run(
    agent,
    conversationId,
    "它什么时候能到？");
```

第二次执行时，Runner 会根据 `conversationId` 找到之前的消息，并在配置范围内将历史提供给模型。因此
模型可以知道“它”指的是订单 `O-1001`。

不同用户或不同业务会话必须使用不同的 `conversationId`，否则可能造成上下文串线和数据泄露。

## 聊天记录与模型上下文

业务保存的聊天记录和本次发送给模型的上下文不是同一个概念：

| 内容 | 用途 | 是否受窗口配置影响 |
| --- | --- | --- |
| 完整聊天记录 | 页面展示、业务留存和审计 | 否 |
| 模型上下文 | 帮助模型处理当前请求 | 是 |

例如，页面可以展示最近一年的完整会话，但模型每次只读取最近 6 轮。调整
`maxAttachedTurns(...)` 或 `maxAttachedMessages(...)` 只会改变模型本次看到的范围，不会清空聊天页面
或删除数据库中的原始消息。

表单和审批等页面状态也可以出现在聊天时间线中，但不会作为普通对话内容发送给模型。

## 设置上下文窗口

Agents-Flex 默认最多附带最近 10 轮、100 条消息。多数应用可以先使用默认值，根据实际对话长度、模型
限制和成本监控再进行调整。

### 按轮数限制

```java
.maxAttachedTurns(6)
```

轮数最接近用户对话的语义，通常应作为主要限制。值越大，模型能看到的历史越多，但请求长度和成本也
会增加。

### 按消息数量限制

```java
.maxAttachedMessages(40)
```

一轮任务可能包含多次模型回复和工具结果，因此只限制轮数仍可能产生很长的上下文。消息数量限制用于
防止这种情况。

Runner 会尽量保留完整的近期轮次，而不是从一组相关消息中间截断。当前正在执行的任务也会优先保持
完整，所以最终消息数在必要时可能超过配置值。

### 按 Token 数限制

不同消息的长度差异很大，需要更精确控制时，可以增加 Token 限制：

```java
Agent agent = Agent.builder("support-agent")
    .chatModel(chatModel)
    .maxAttachedTurns(10)
    .maxAttachedMessages(100)
    .maxAttachedTokens(
        16_000,
        messages -> tokenCounter.estimate(messages))
    .build();
```

| 参数 | 作用 |
| --- | --- |
| `16_000` | 本次历史消息允许使用的最大估算 Token 数 |
| `tokenCounter` | 由应用提供的 Token 估算器，应与所用模型尽量接近 |

Token 限制默认为关闭状态。不同模型的分词方式不同，因此 Agents-Flex 不会假设一种固定算法；启用该
限制时必须由应用提供估算器。

### 如何选择限制

| 应用情况 | 建议 |
| --- | --- |
| 对话较短，基本没有工具 | 先使用默认值 |
| 只需要理解最近几次交流 | 适当降低 `maxAttachedTurns` |
| 每轮会产生多条工具结果 | 同时设置 `maxAttachedMessages` |
| 接近模型上下文上限 | 再增加 `maxAttachedTokens` |
| 很早以前的信息仍然重要 | 使用摘要，不要只扩大窗口 |

窗口不应设置得越大越好。过多无关历史可能稀释当前问题，让模型更难找到真正重要的信息。

## 精简已完成的工具过程

一次工具任务可能包含用户问题、工具调用过程、工具返回内容和最终回复。对于已经完成的较早任务，模型
通常只需要知道用户问了什么以及最终结论，不需要反复读取全部中间过程。

可以保留最近几轮的完整过程，同时精简更早的工具过程：

```java
Agent agent = Agent.builder("support-agent")
    .chatModel(chatModel)
    .compressionPolicy(AgentContextCompressionPolicy.builder()
        .compactCompletedToolTurns(true)
        .keepRecentTurns(3)
        .build())
    .build();
```

| 配置 | 作用 |
| --- | --- |
| `compactCompletedToolTurns(true)` | 对较早且已经完成的工具任务，只保留问题和最终回复 |
| `keepRecentTurns(3)` | 最近 3 轮保留完整内容，不参与精简 |

该方式不调用额外的大模型，也不会删除完整聊天记录。默认已经开启已完成工具过程的精简，并保护最近
2 轮；只有需要调整保护范围或关闭该行为时才需要显式配置。

## 使用模型生成历史摘要

如果会话持续很久，仅保留最近几轮可能会丢失早期的重要事实。例如，用户在很早之前指定了预算、交付
日期或不可变更的业务约束。此时可以使用一个模型将较早历史整理成摘要：

```java
AgentContextCompressor compressor = AgentContextCompressors.model(
    summaryModel,
    "请用中文总结历史对话，保留业务事实、实体 ID、"
        + "用户约束、审批结果和未完成事项，不要编造信息。");

Agent agent = Agent.builder("support-agent")
    .chatModel(chatModel)
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
| `summaryModel` | 用于生成摘要的模型，可以与业务模型相同，也可以使用成本更低的模型 |
| 摘要指令 | 明确哪些事实必须保留，以及禁止编造内容 |
| `keepRecentTurns(3)` | 保留近期原文，只摘要更早的历史 |
| `USE_ORIGINAL` | 摘要失败时继续使用未摘要的历史，而不是直接中止当前请求 |

摘要能够节省上下文，但也可能遗漏细节或产生错误。订单金额、权限、账户余额等关键事实仍应从业务系统
查询，不能只依赖聊天摘要。

普通摘要可能在每次构建长上下文时处理较早历史。需要长期运行并避免重复摘要时，可以使用增量压缩，
详细配置见[上下文压缩](./context-compression)。

## 控制工具结果

工具结果也会进入模型上下文。如果搜索、日志或报表工具直接返回大量原文，即使历史轮数不多，也可能
迅速占满可用空间。

更合适的工具设计包括：

- 查询接口提供分页、筛选条件和返回条数上限；
- 搜索工具先返回摘要和结果 ID，需要时再查询详情；
- 报表和导出文件保存到文件存储，工具只返回文件 ID 或受控链接；
- 返回内容可能不完整时，提供 `hasMore`、`nextCursor` 等明确字段；
- 为工具结果配置最大字符数，防止意外返回超大内容。

工具结果的字符限制和超限处理方式见[工具执行控制](./tool-execution)。

## 图片、音频和文件

图片、音频、视频和文件也可能成为上下文的一部分。使用这些内容时需要同时考虑：

1. 当前模型是否支持对应的输入类型。
2. 文件是否超过模型服务允许的大小。
3. 长期保存是否符合用户授权和数据保留要求。
4. 多租户环境下是否进行了访问隔离。

大文件通常应保存到对象存储，消息中只保留经过鉴权、具有有效期的引用。不要把大量二进制内容长期
复制到每一轮聊天记录中。

## 会话管理

生产环境中应由业务系统管理会话生命周期：

- 为每段连续对话生成稳定且不可猜测的 `conversationId`；
- 确保同一个 ID 始终读取同一份聊天记录；
- 不要在不同用户或租户之间复用会话 ID；
- 持久化聊天记录，不能依赖 `DefaultChatMemory` 的进程内数据；
- 同一会话已有任务正在执行或等待时，应先完成、恢复或取消该任务，再提交下一项任务。

当同一会话已经存在未结束的任务时，Runner 会拒绝并发创建新的任务。业务接口应将这种情况转换为
明确的“会话忙碌”提示、排队处理或稍后重试，而不是覆盖正在执行的内容。

`ChatMemory` 的持久化实现方式见 [Memory 记忆](../chat/memory)，Agent 任务状态的保存方式见
[任务快照持久化](./store)。等待任务的处理方式见[挂起和恢复](./suspend-resume)。

## 配置建议

1. 先使用默认窗口运行真实场景，再根据 Token、延迟和回答质量调整。
2. 优先限制轮数和工具结果大小，确有长期记忆需求时再增加模型摘要。
3. 摘要中应保留实体 ID、数字、用户约束、审批结果和未完成事项。
4. 用户权限、账户状态和订单数据应从业务系统查询，不要把上下文当作事实数据库。
5. 监控每次请求的消息数、Token 数、摘要次数、摘要失败率和大型工具结果。
6. 不要把 API Key、数据库连接或其他密钥写入聊天记录和摘要。

## 相关文档

- 配置即时摘要和增量压缩：[上下文压缩](./context-compression)
- 限制工具返回内容：[工具执行控制](./tool-execution)
- 限制任务总 Token 消耗：[运行限制与预算](./budget)
- 实现持久化聊天记录：[Memory 记忆](../chat/memory)
- 持久化 Agent 任务状态：[任务快照持久化](./store)
- 处理等待中的会话任务：[挂起和恢复](./suspend-resume)

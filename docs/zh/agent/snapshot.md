---
title: 任务快照（Snapshot）
description: 保存 Agent 任务的最新进度，使任务在等待、重试、应用重启或节点切换后能够继续处理。
---

# 任务快照（Snapshot）

## 概述

有些 Agent 任务可以在几秒内完成，但也有很多任务不能一次执行到底。例如：

- 退款工具需要等待主管审批；
- 创建工单前，需要等待用户补充表单；
- 外部服务暂时不可用，任务需要稍后重试；
- 后台正在处理任务时，应用发生重启；
- 多个服务实例共同处理任务，下一次可能由另一个实例接手。

如果任务进度只保存在当前 Java 对象中，应用重启后就会丢失。业务系统也无法仅凭任务 ID 判断已经执行到
哪里、正在等待什么，以及最终是否成功。

Snapshot 用来保存一项 Agent 任务的最新进度。Agents-Flex 中对应的 Java 类型是
`AgentTurnSnapshot`。可以将它理解为任务的“进度存档”：应用稍后根据任务 ID 读取存档，就能重新得到
对应的 `AgentTurn`。

```text
任务执行到稳定位置
        ↓
Runner 自动保存 Snapshot
        ↓
应用重启或任务稍后继续
        ↓
根据 turnId 恢复最新进度
```

正常使用时，不需要在每次模型调用或工具调用后手动创建 Snapshot。`AgentRunner` 会在适合恢复的位置自动
保存。业务系统主要负责保存 `turnId`，并为 Runner 配置合适的任务存储。

## 适用场景

| 场景 | Snapshot 提供的能力 |
| --- | --- |
| 人工审批或表单输入 | 保存当前等待内容，用户稍后提交后继续原任务 |
| 自动重试 | 记录失败位置和下次可以执行的时间 |
| 应用重启 | 从持久化存储读取任务最新进度 |
| 后台 Worker | 让后台执行器领取并继续待处理任务 |
| 多实例部署 | 让不同实例看到同一份任务状态 |
| 状态查询 | 根据任务 ID 查询等待、完成、失败或取消状态 |

只有一次性、单进程的简单调用，并且不需要在应用重启后查询任务时，可以先使用默认的内存存储。生产环境
中的审批、后台任务和多实例部署通常需要持久化 Snapshot。

## 快速开始

下面使用框架提供的内存实现演示保存和恢复。示例中的 `agent` 表示已经创建好的 Agent。

### 1. 配置任务存储和 AgentLoader

```java
AgentTurnStore turnStore =
    new InMemoryAgentTurnStore();

AgentLoader agentLoader =
    new InMemoryAgentLoader(agent);

AgentRunner runner = AgentRunner.builder()
    .turnStore(turnStore)
    .agentLoader(agentLoader)
    .build();
```

| 配置 | 作用 |
| --- | --- |
| `AgentTurnStore` | 按任务 ID 保存和读取 Snapshot |
| `InMemoryAgentTurnStore` | 将 Snapshot 保存在当前进程内，适合学习和测试 |
| `AgentLoader` | 恢复时，根据 Agent ID 和版本找回完整 Agent 配置 |
| `turnStore(...)` | 将任务存储交给当前 Runner 使用 |
| `agentLoader(...)` | 将 Loader 交给当前 Runner 使用 |

Snapshot 只保存任务进度，不会把模型客户端和工具函数一起保存。因此，恢复任务时还需要
`AgentLoader` 找回任务原来使用的 Agent 版本。

### 2. 执行任务并保存任务 ID

```java
AgentTurn turn = runner.run(
    agent,
    "查询订单 O-1001 的状态");

String turnId = turn.getId();
```

`runner.run(...)` 执行过程中会自动保存进度。业务数据库、页面或后续回调只需要保留 `turnId`，不应长期
保存当前内存中的 `AgentTurn` 对象。

### 3. 根据任务 ID 恢复

```java
AgentTurn restored = runner.restore(turnId);

System.out.println(restored.getStatus());
System.out.println(restored.getFinalOutput());
```

`restore(...)` 读取最新 Snapshot，并重新得到可以查询的 `AgentTurn`。它首先用于获得任务当前状态，不会
把一个正在等待审批或表单的任务强行继续执行。

::: warning 内存存储不能跨重启恢复
`InMemoryAgentTurnStore` 的数据会在应用退出后丢失。生产环境需要使用 JDBC、Redis 或业务自己实现的共享
存储，具体配置见 [任务快照持久化](./store)。
:::

## Snapshot 保存什么

Snapshot 会保存继续处理任务所需的信息：

| 内容 | 用途 |
| --- | --- |
| 任务 ID | 后续查询和恢复同一项任务 |
| Agent ID 和版本 | 恢复任务创建时使用的 Agent 配置 |
| 当前状态 | 判断任务正在运行、等待、完成、失败还是取消 |
| 对话与工具消息 | 让模型和工具从已经完成的位置继续 |
| 待处理的工具调用 | 恢复后继续处理模型已经选中的工具 |
| 审批、表单或外部工具等待信息 | 确认任务正在等待什么 |
| 执行次数和 Token 等统计 | 继续执行限制并支持状态查询 |
| 重试时间和取消标记 | 支持后台调度和取消请求 |
| metadata | 保存订单 ID、用户 ID 等业务附加信息 |
| 最终结果或错误摘要 | 查询任务执行结果 |

这里保存的是数据，而不是正在运行的 Java 对象。

## Snapshot 不保存什么

以下对象不会直接保存在 Snapshot 中：

- 大模型客户端 `ChatModel`；
- Java 工具函数和外部服务客户端；
- Middleware 和事件监听器；
- 数据库连接、线程池和 Spring Bean；
- 原始异常对象。

这些对象通常包含网络连接、回调函数或进程资源，不能依靠任务存档直接恢复。Runner 会根据 Snapshot 中的
Agent ID 和版本，通过 `AgentLoader` 重新获取完整 Agent。具体方式见 [Agent 加载与版本](./agent-loader)。

因此，历史 Agent 版本不能在仍有未完成任务时随意删除。找不到原版本时，应明确报告加载失败，不能自动
换成最新版本继续执行。

## 何时自动保存

Runner 会在任务已经形成明确进度的位置自动保存，例如：

- 任务刚刚创建；
- 模型已经决定调用哪些工具；
- 一个工具已经完成并产生结果；
- 任务开始等待审批、表单或外部工具；
- 用户提交信息，任务准备继续；
- 已经安排下一次自动重试；
- 任务完成、失败、取消或达到执行限制。

这些保存位置的目标是：恢复后从最近已经确认的位置继续，而不是从头重复整个任务。

即使如此，写数据库、退款、发货等工具仍然必须实现幂等。工具可能已经完成外部操作，但应用在保存最新
进度前发生故障，此时恢复后可能再次执行同一次工具调用。具体做法见[工具运行上下文](./tool-context)。

## 恢复后如何继续

恢复和继续执行是两个不同动作。应先查看恢复后的状态，再选择对应操作：

| 当前情况 | 建议操作 |
| --- | --- |
| 只想查询任务状态或结果 | 调用 `runner.restore(turnId)` |
| 任务可以直接继续运行 | 调用 `runner.runUntilBlocked(turnId)` |
| 正在等待人工审批 | 提交审批结果并恢复原任务 |
| 正在等待用户表单 | 提交表单数据并恢复原任务 |
| 正在等待自动重试 | 由后台 Worker 在到期后领取并继续 |
| 已经完成、失败或取消 | 展示最终状态，不要再次执行 |

审批和表单必须使用当前任务提供的等待信息提交，不能只恢复后直接跳过。完整流程分别见
[人工审批](./human-approval)、[表单输入](./form-input)和[挂起和恢复](./suspend-resume)。

## Snapshot 与聊天记录的区别

Snapshot 和聊天记录都可能包含消息，但用途不同：

| 内容 | 主要用途 |
| --- | --- |
| Snapshot | 让一项具体任务安全地继续执行 |
| ChatMemory | 保存多轮会话，让下一项任务理解之前聊过什么 |

例如，一个退款任务正在等待审批时，Snapshot 保存等待状态和待执行的退款工具；用户下一次说“继续刚才的
问题”时，ChatMemory 用来帮助模型理解“刚才”指什么。

Snapshot 不能替代完整的业务聊天记录，ChatMemory 也不能替代任务恢复所需的 Snapshot。连续对话的配置
见[上下文管理](./context-management)。

## metadata 的保存要求

使用 `AgentTurnOptions.metadata(...)` 添加的业务信息会随任务进度保存：

```java
AgentTurnOptions options = AgentTurnOptions.builder()
    .metadata("orderId", "O-1001")
    .metadata("userId", "U-1001")
    .build();

AgentTurn turn = runner.run(
    agent,
    "查询订单状态",
    options);
```

适合保存的值包括字符串、数字、布尔值，以及由这些简单值组成的列表和 Map。

不要将以下内容放入 metadata：

- 密码、API Key 和访问令牌；
- 数据库连接、HTTP 客户端和 Spring Bean；
- 线程、文件句柄或回调函数；
- 无法被当前 Store 正确序列化的对象。

如果必须保存业务自定义对象，需要先确认所用 Store 的序列化规则，并做好版本兼容与安全限制。多数情况
下，保存业务 ID，再在需要时从业务数据库查询完整对象会更稳妥。

## 手动保存

大多数业务不需要手动保存 Snapshot。只有自定义编排确实在 Runner 自动保存位置之外修改了可持久化任务
信息时，才应考虑调用：

```java
AgentTurnSnapshot saved =
    runner.saveSnapshot(turn);

System.out.println(
    saved.getState().getStatus());
```

`turn.toSnapshot()` 只生成当前内存状态的快照对象，不会将它写入 `AgentTurnStore`：

```java
AgentTurnSnapshot localCopy =
    turn.toSnapshot();
```

如果目的是持久化，应使用 `runner.saveSnapshot(turn)`，不要把 `toSnapshot()` 误认为保存操作。也不要绕过
Runner 随意修改 Snapshot 后覆盖 Store，否则可能破坏任务状态或覆盖其他执行者提交的新进度。

## 并发更新保护

审批回调、后台 Worker 和多个应用实例可能同时读取同一项任务。如果两个执行者都根据旧进度保存结果，后
保存的一方可能覆盖前一方。

`AgentTurnStore` 使用递增版本号检查更新是否基于最新数据。发生版本冲突时，表示其他执行者已经先更新了
任务。当前执行者应停止使用旧对象并重新读取最新状态，而不是强制覆盖。

使用框架提供的 Store 时，这项检查由框架处理。只有实现自定义 `AgentTurnStore` 时，才需要正确实现
`save(snapshot, expectedVersion)` 的原子比较和更新；新记录使用的初始期望版本为 `-1`。

版本冲突保护的是任务进度，不能代替业务工具的幂等控制。即使 Snapshot 没有被重复保存，外部系统也可能
已经收到过同一笔请求。

## 数据安全与保留

Snapshot 可能包含用户消息、工具参数、表单数据、业务 metadata 和错误摘要，应按照业务数据处理：

1. 限制只有授权服务和用户可以按 `turnId` 查询任务；
2. 存储和传输敏感数据时使用合适的加密措施；
3. 日志中不要直接输出完整 Snapshot；
4. 为已完成、失败和取消的任务设置明确的保留期限；
5. 删除任务状态时，与审计记录、聊天记录和业务结果的保留规则协调。

Snapshot 中的任务版本号用于并发更新，与 Agent 的配置版本不是同一个概念：前者随每次保存递增，后者在
发布新 Agent 配置时变化。

## 版本升级

升级 Agents-Flex 前，应检查新版本是否改变 Snapshot 的字段或序列化格式，并先在测试环境验证历史数据
可以读取。不要在未确认兼容性的情况下，让不同框架版本的 Runner 同时读写同一批任务数据。

从早期 `AgentRun` 数据升级到当前 `AgentTurn` 时，框架不会自动转换旧 Snapshot。已有系统应先完成旧
任务，或由业务在停机窗口迁移仍需保留的任务数据。

## 使用建议

1. 正常业务依赖 Runner 自动保存，不要在每个步骤后重复手动保存。
2. 业务系统保存稳定的 `turnId`，需要查询时重新恢复最新状态。
3. 生产环境使用所有实例都能访问的持久化 Store。
4. 保留未完成任务所引用的 Agent 历史版本。
5. 写操作同时做好 Snapshot 并发保护和业务幂等。
6. 对 Snapshot 中的敏感数据设置访问控制和保留期限。

## 相关文档

- 了解任务对象和状态：[AgentTurn](./agent-turn)
- 根据 ID 和版本恢复 Agent：[Agent 加载与版本](./agent-loader)
- 配置 JDBC、Redis 或自定义 Store：[任务快照持久化](./store)
- 配置后台任务恢复：[后台任务 Worker](./worker)
- 处理审批和表单等待：[挂起和恢复](./suspend-resume)
- 防止工具重复执行：[工具运行上下文](./tool-context)

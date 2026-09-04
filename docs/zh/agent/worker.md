---
title: 后台任务 Worker
description: 使用 AgentWorker 在后台执行耗时任务，并支持自动重试、故障接管和多实例部署。
---

# 后台任务 Worker

## 概述

有些 Agent 任务只需要几秒，例如查询订单状态，可以直接在当前请求中等待结果。但生成月度报告、整理大量
资料或调用多个外部系统，可能需要几分钟甚至更久。如果一直占用 HTTP 请求，用户容易遇到页面超时，服务
也很难平稳处理大量任务。

`AgentWorker` 用来在后台执行这类任务。接口收到请求后，只需先创建任务并返回任务 ID；Worker 会在后台
找到待处理任务并执行，页面可以根据任务 ID 查询最新状态和结果。

```text
接口创建任务并返回任务 ID
            ↓
      任务保存到 Store
            ↓
     Worker 在后台执行
            ↓
  页面查询任务状态和结果
```

除了让请求快速返回，Worker 还适合处理以下情况：

- 外部服务暂时不可用，任务需要稍后自动重试；
- 任务等待人工审批或用户填写表单，收到结果后再继续；
- 应用部署了多个实例，需要共同处理任务；
- 某个实例意外停止，需要由其他实例接手未完成的任务。

Worker 只负责“何时领取和执行任务”。Agent 如何调用模型和工具，仍然由 `AgentRunner` 负责；任务进度则
由 `AgentTurnStore` 保存。

## 何时需要使用

可以根据任务耗时和接口形式选择执行方式：

| 场景 | 推荐方式 |
| --- | --- |
| 命令行程序、本地示例、几秒内完成的同步请求 | `runner.run(...)` |
| HTTP 接口需要立即返回任务 ID | `runner.start(...)` + `AgentWorker` |
| 任务需要等待审批、表单或自动重试 | `runner.start(...)` + `AgentWorker` |
| 任务需要在应用重启后继续 | Worker + 持久化 Store |
| 多个服务实例共同处理后台任务 | 多个 Worker + 共享 Store |

`runner.run(...)` 会在当前线程执行任务；`runner.start(...)` 只创建任务，不会自动启动后台线程。使用
`start(...)` 时，必须启动 Worker，任务才会真正执行。

## 快速开始

下面先使用内存 Store 跑通完整流程。该配置适合本地学习，应用重启后任务会丢失；正式环境的配置方式见
[生产环境配置](#生产环境配置)。

### 1. 创建 AgentRunner

```java
Agent reportAgent = Agent.builder("报告助手")
    .id("report-agent")
    .version("1.0.0")
    .chatModel(chatModel)
    .build();

AgentTurnStore turnStore = new InMemoryAgentTurnStore();
AgentLoader agentLoader = new InMemoryAgentLoader(reportAgent);

AgentRunner runner = AgentRunner.builder()
    .turnStore(turnStore)
    .agentLoader(agentLoader)
    .build();
```

这里有两个 Worker 必需的配置：

| 配置 | 作用 |
| --- | --- |
| `turnStore` | 保存任务进度，让 Worker 能够找到待处理任务 |
| `agentLoader` | Worker 执行或恢复任务时，根据 ID 和版本找到原来的 Agent |

`chatModel` 表示已经创建好的模型客户端。Agent 的基础创建方式见 [Agent](./agent)，Loader 的详细说明见
[Agent 加载与版本](./agent-loader)。

### 2. 创建后台任务

```java
AgentTurn queued = runner.start(reportAgent, "生成本月销售报告");
String turnId = queued.getId();
```

`start(...)` 会保存任务并立即返回。此时只是排好了一个待执行任务，还没有开始调用模型。

业务接口通常把 `turnId` 返回给前端，例如：

```json
{
  "turnId": "这里是任务 ID",
  "status": "READY"
}
```

### 3. 启动 Worker

```java
AgentWorkerOptions workerOptions =
    AgentWorkerOptions.builder("report-worker-1", 30_000)
        .pollIntervalMillis(1_000)
        .batchSize(10)
        .maxConcurrentTurns(2)
        .build();

AgentWorker worker = new AgentWorker(runner, workerOptions);
worker.startPolling();
```

这段配置的含义如下：

| 配置 | 示例值 | 说明 |
| --- | ---: | --- |
| `workerId` | `report-worker-1` | 当前 Worker 的唯一名称，用于区分不同进程或实例 |
| `leaseMillis` | `30_000` | Worker 一次取得任务执行权的有效时间，单位为毫秒 |
| `pollIntervalMillis` | `1_000` | 每隔多久检查一次新任务，单位为毫秒 |
| `batchSize` | `10` | 每轮最多领取多少个任务 |
| `maxConcurrentTurns` | `2` | 当前 Worker 最多同时执行多少个任务 |

刚开始接入时，建议把 `maxConcurrentTurns` 设为 `1`，确认模型限流、工具连接池和数据库容量都能承受后，
再逐步增加。`batchSize` 可以大于并发数，多领取的任务会分批执行。

`startPolling()` 会使用上面的配置持续检查任务。重复调用不会启动多个轮询线程。

### 4. 查询任务结果

前端拿到任务 ID 后，可以通过业务接口定期查询。接口内部使用 `restore(...)` 读取最新进度：

```java
AgentTurn latest = runner.restore(turnId);

if (latest.getStatus() == AgentTurnStatus.COMPLETED) {
    System.out.println(latest.getFinalOutput());
} else {
    System.out.println("当前状态：" + latest.getStatus());
}
```

常见状态包括 `READY`（等待执行）、`RUNNING`（执行中）、`COMPLETED`（已完成）、`FAILED`（执行失败），
以及等待审批或表单输入等状态。完整状态说明见 [AgentTurn](./agent-turn)。

### 5. 关闭 Worker

应用停止时，应关闭 Worker，让它停止领取新任务：

```java
worker.close();
```

`AgentWorker` 实现了 `AutoCloseable`，也可以交给 Spring 等容器在应用关闭阶段调用 `close()`。

## 手动执行一轮任务

如果应用已经使用 Quartz、XXL-JOB 或其他定时任务框架，可以不启动 Worker 自带的持续轮询，而是由外部
调度器定期执行一轮：

```java
try (AgentWorker worker = new AgentWorker(runner, workerOptions)) {
    List<AgentTurn> processed = worker.pollAndRun(10);
    System.out.println("本轮处理任务数：" + processed.size());
}
```

`pollAndRun(10)` 表示本轮最多领取并处理 10 个任务。该方法会等待这一轮任务处理完毕后再返回，返回值是
本轮实际处理过的任务列表。

同一个 Worker 实例不能同时执行多次 `pollAndRun(...)`。外部调度周期应避免重叠；需要增加吞吐量时，
应调整 `maxConcurrentTurns`，或者启动多个使用不同 ID 的 Worker。

## Worker 会处理哪些任务

Worker 会领取当前已经可以继续的任务，例如：

- 通过 `runner.start(...)` 创建、还未开始执行的任务；
- 已到达重试时间、可以再次尝试的任务；
- 已经提交审批结果或表单内容、可以继续执行的任务；
- 上一个 Worker 中断后，执行权已经到期的任务。

正在等待人工审批、用户填写表单或外部工具返回结果的任务，不会被反复执行。相关结果提交后，任务重新变为
可执行状态，Worker 才会继续处理。

## 并发与处理速度

`batchSize` 和 `maxConcurrentTurns` 容易混淆，它们控制的是两件不同的事：

```text
batchSize = 10           本轮最多处理 10 个任务
maxConcurrentTurns = 2  同一时刻最多执行 2 个任务
```

更高的并发不一定更快。模型服务通常有并发数或请求速率限制，数据库和业务工具也有容量上限。并发过高可能
导致大量限流和重试，反而延长任务完成时间。

建议从较小的数值开始，根据模型限流、平均任务耗时、数据库连接池和工具服务容量逐步调整。多个应用实例的
总并发数等于所有 Worker 并发数之和，也需要一并计算。

## 生产环境配置

内存 Store 只能让当前进程中的 Worker 找到任务。要支持应用重启和多实例部署，应完成以下配置：

1. 将 `InMemoryAgentTurnStore` 换成 JDBC 或 Redis Store；
2. 让所有应用实例连接同一个 Store；
3. 让所有实例都能加载任务使用的 Agent 版本；
4. 为每个 Worker 设置不同的 `workerId`。

```text
Worker A ─┐
Worker B ─┼─→ 共享的 JDBC 或 Redis Store
Worker C ─┘
```

Store 的配置方式见[任务快照持久化](./store)，Agent 版本的配置方式见
[Agent 加载与版本](./agent-loader)。

### 任务执行权

多个 Worker 同时检查任务时，需要保证同一任务不会被正常领取两次。Agents-Flex 的做法可以简单理解为：

1. Worker 领取任务时，获得一张有有效期的“执行凭证”；
2. 执行时间较长时，Worker 会自动延长有效期；
3. Worker 意外停止且凭证到期后，其他 Worker 可以接手；
4. 已经过期的 Worker 不能再覆盖新 Worker 保存的任务进度。

这张临时执行凭证在 API 中称为 **Lease（租约）**，`leaseMillis` 就是每次租约的有效时间。默认情况下，
Worker 大约每经过三分之一租期自动续期。通常只需要把租期设置得明显长于短暂的网络抖动，同时又不要长到
严重影响故障接管。

如果确实需要调整续期间隔，可以使用：

```java
.leaseRenewalFraction(1.0 / 3.0)
```

该值表示续期间隔占租期的比例，范围是大于 `0` 且不超过 `1`。大多数应用保持默认值即可。

### 业务操作仍需防止重复

Worker 的执行凭证可以保护任务进度，但不能撤回已经发送到外部系统的请求。例如，一个 Worker 已经发出
“退款”请求，却在保存最新进度前断网；其他 Worker 接手后，可能再次调用退款接口。

因此，退款、发货、扣款、发送消息等会产生业务影响的工具，仍应使用 `turnId`、`toolCallId` 或业务单号
作为幂等键，让相同请求重复到达时只生效一次。具体做法见[工具运行上下文](./tool-context)。

## 优雅关闭

`close()` 会停止新的自动轮询，但不保证强制终止正在进行的模型请求或工具调用。应用发布或缩容时，推荐
按下面的顺序处理：

1. 停止接收新的后台任务；
2. 调用 `worker.close()`，停止领取新任务；
3. 为正在执行的任务预留完成时间；
4. 再关闭应用进程。

部署平台的终止宽限时间应覆盖常见的模型和工具调用超时。如果进程仍然意外退出，其他 Worker 会在原执行权
到期后接手任务。

## 监控与排查

生产环境建议至少关注以下信息：

| 指标 | 可以发现的问题 |
| --- | --- |
| 待处理任务数 | 任务是否持续堆积 |
| 最老待处理任务的等待时间 | 是否有任务长期得不到执行 |
| 任务领取、完成和失败数量 | Worker 是否正常工作 |
| 重试任务的实际延迟 | 重试是否按预期执行 |
| 执行权续期失败次数 | Store、网络或 Worker 是否不稳定 |
| Worker 最近成功轮询时间 | Worker 是否停止运行 |

排查“任务一直不执行”时，可以依次确认：Worker 是否已经调用 `startPolling()`、任务是否仍在等待外部输入、
Worker 与创建任务的接口是否连接同一个 Store，以及 AgentLoader 是否能加载对应版本。

## 使用建议

- 短任务优先使用 `runner.run(...)`，确认需要后台执行后再引入 Worker；
- `runner.start(...)` 返回后应把 `turnId` 交给调用方，用于查询和后续操作；
- 本地学习可以使用内存 Store，生产环境使用 JDBC 或 Redis；
- 每个 Worker 使用唯一 ID，并统一规划所有实例的总并发数；
- 对退款、发货和发送消息等工具做好业务幂等；
- 应用关闭时显式调用 `close()`，并预留正在执行任务的完成时间。

## 相关文档

- [AgentRunner](./agent-runner)：创建、执行和恢复 Agent 任务
- [AgentTurn](./agent-turn)：查看任务状态和结果
- [任务快照持久化](./store)：使用 JDBC 或 Redis 保存任务进度
- [Agent 加载与版本](./agent-loader)：让 Worker 找到任务使用的 Agent
- [错误处理与重试](./retry)：配置失败后的重试方式
- [人工审批](./human-approval)：提交审批结果后继续后台任务
- [表单输入](./form-input)：提交用户补充信息后继续后台任务

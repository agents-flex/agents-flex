---
title: 可观测性
description: 通过任务状态、运行事件、日志、指标和调用链了解 Agent 是否正常运行，并快速定位等待、失败和性能问题。
---

# 可观测性

## 概述

Agent 应用上线后，用户反馈通常不是一段完整的错误信息，而是“任务一直没结束”“刚才失败了”或“今天明显
变慢了”。一次 Agent 任务又可能经过模型调用、工具执行、人工审批和自动重试，只看最终回答很难判断问题
发生在哪里。

可观测性的作用，就是让系统在运行时留下足够的线索，帮助开发和运维人员回答：

- 任务现在处于什么状态？
- 为什么正在等待，接下来需要谁处理？
- 失败发生在模型、工具，还是外部服务？
- 一次任务调用了多少次模型和工具，消耗了多少 Token？
- 最近的失败率和处理时间是否异常？
- 后台 Worker 是否还在正常领取任务？

它不是某一个监控页面，也不是简单地打印模型内容。对于 Agent 任务，可以从三个层次逐步建设：

| 层次 | 使用的能力 | 主要回答的问题 |
| --- | --- | --- |
| 当前状态 | `AgentTurn`、`AgentTurnStore` | 任务现在执行到哪里 |
| 过程变化 | `AgentEventListener`、业务日志 | 任务刚刚发生了什么 |
| 详细调用 | Agents-Flex Observability | 哪次模型、工具或 HTTP 调用最慢、为什么失败 |

刚开始接入时，先做好任务 ID、状态查询和关键事件日志，通常就能解决大部分日常排查问题。需要跨服务调用链、
耗时分析和监控大盘时，再接入基于 OpenTelemetry 的 Observability 模块。

## 建议的接入顺序

可以按照下面的顺序逐步完善，不需要第一天就建设完整监控平台：

1. 为每个任务保留 `turnId`，并让用户反馈或客服工单能够提供这个 ID；
2. 使用 `AgentEventListener` 记录开始、等待、完成和失败等关键事件；
3. 提供根据 `turnId` 查询 `AgentTurn` 最新状态的接口；
4. 统计任务量、成功率、处理时间和等待任务数量；
5. 最后接入 Trace，分析模型、工具和 HTTP 调用的详细耗时。

本地开发可以把信息输出到日志。生产环境再根据现有基础设施，将日志、指标和 Trace 发送到公司的日志平台、
APM 或 OpenTelemetry Collector。

## 快速开始

### 1. 记录关键运行事件

下面的监听器只记录任务级关键事件，适合作为最小起点：

```java
Set<AgentEventType> keyEvents = EnumSet.of(
    AgentEventType.TURN_STARTED,
    AgentEventType.TURN_SUSPENDED,
    AgentEventType.TURN_RESUMED,
    AgentEventType.RETRY_SCHEDULED,
    AgentEventType.TURN_COMPLETED,
    AgentEventType.TURN_FAILED,
    AgentEventType.TURN_CANCELLED,
    AgentEventType.BUDGET_EXCEEDED,
    AgentEventType.MAX_ITERATIONS_REACHED,
    AgentEventType.MAX_STEPS_REACHED
);

AgentRunner runner = new AgentRunner()
    .addEventListener(event -> {
        if (!keyEvents.contains(event.getType())) {
            return;
        }

        logger.info(
            "agentEvent={} turnId={} agentId={} status={} stepCount={}",
            event.getType(),
            event.getTurnId(),
            event.getAgentId(),
            event.getData().get("status"),
            event.getData().get("stepCount")
        );
    });
```

`logger` 表示应用已有的 SLF4J Logger。这里没有记录用户问题、模型回答或工具参数，可以降低敏感信息进入
日志的风险。

监听器默认在任务执行线程中运行，应快速返回。写入较慢的外部系统时，应交给业务消息队列或异步执行器。
事件类型和异步配置方式见 [AgentEventListener](./agent-event-listener)。

### 2. 保存业务关联信息

用户反馈问题时，只有一句“刚才失败了”通常无法定位任务。创建任务时，可以把业务请求 ID、用户 ID 或任务
类型保存到 metadata，并在业务日志中记录它们与 `turnId` 的关系：

```java
String requestId = UUID.randomUUID().toString();

AgentTurnOptions options = AgentTurnOptions.builder()
    .metadata("requestId", requestId)
    .metadata("tenantId", "tenant-1001")
    .metadata("taskType", "monthly-report")
    .build();

AgentTurn turn = runner.start(
    agent,
    "生成本月销售报告",
    options
);

logger.info(
    "agent task created requestId={} turnId={}",
    requestId,
    turn.getId()
);
```

metadata 会随任务进度一起保存，恢复任务后仍然可以读取。不要把密码、访问令牌或大段业务正文放入 metadata。

建议至少保留这些关联标识：

| 标识 | 作用 |
| --- | --- |
| `turnId` | 定位一次具体 Agent 任务 |
| `agentId`、`agentVersion` | 确认任务使用了哪个 Agent 配置 |
| `requestId` | 与业务接口日志关联 |
| `tenantId`、`userId` | 在有权限控制的前提下定位租户和用户 |
| `toolCallId` | 定位一次具体工具调用 |

### 3. 查询任务的最新状态

页面查询、客服排查或后台巡检时，可以根据 `turnId` 读取任务当前状态：

```java
AgentTurn latest = runner.restore(turnId);

System.out.println("状态：" + latest.getStatus());
System.out.println("执行步骤：" + latest.getStepCount());
System.out.println("模型调用：" + latest.getIterationCount());
System.out.println("工具调用：" + latest.getToolCallCount());
System.out.println("重试次数：" + latest.getRetryCount());
System.out.println("Token 用量：" + latest.getTotalTokens());

if (latest.getSuspension() != null) {
    System.out.println(
        "等待原因：" + latest.getSuspension().getMessage()
    );
}
```

这些信息分别说明任务当前在哪里、已经进行了多少工作，以及是否正在等待外部操作。`restore(...)` 读取的是
最新任务快照，不需要依赖之前的实时事件仍然存在。

### 4. 查看模型和工具的详细耗时

当任务状态和事件日志还不能解释“为什么慢”时，可以临时将通用 Observability 数据输出到本地日志：

```bash
java \
  -Dagentsflex.otel.enabled=true \
  -Dagentsflex.otel.exporter.type=logging \
  -Dagentsflex.otel.service.name=my-agent-service \
  -jar app.jar
```

| 配置 | 作用 |
| --- | --- |
| `agentsflex.otel.enabled=true` | 开启 Agents-Flex 自动观测 |
| `agentsflex.otel.exporter.type=logging` | 把调用数据输出到应用日志 |
| `agentsflex.otel.service.name` | 标识是哪一个应用产生的数据 |

正常执行一次任务后，日志中可以看到模型、工具和框架 HTTP 请求的调用记录。确认数据正确后，再根据生产环境
选择 OTLP、JDBC 或已有的 OpenTelemetry 配置。完整步骤见
[Observability 快速开始](../observability/getting-started)。

## 根据任务状态排查问题

拿到 `turnId` 后，第一步通常不是搜索所有日志，而是先查询 `AgentTurn` 的最新状态：

| 状态 | 通俗含义 | 优先检查 |
| --- | --- | --- |
| `READY` | 已创建，还没有开始执行 | Worker 是否启动，是否连接同一个 Store |
| `RUNNING` | 正在调用模型或处理工具 | 最近事件、模型和工具调用是否超时 |
| `WAITING_FOR_USER` | 等待用户补充信息 | 页面是否正确展示并提交表单 |
| `WAITING_FOR_APPROVAL` | 等待人工审批 | 审批通知和审批接口是否正常 |
| `WAITING_FOR_TOOL` | 等待外部工具结果 | 外部执行器是否收到请求并回传结果 |
| `RETRY_SCHEDULED` | 等待到指定时间重试 | `nextRunnableAt`、Worker 和重试次数 |
| `COMPLETED` | 已正常完成 | 读取最终结果即可 |
| `FAILED` | 遇到无法继续的错误 | 错误类型、最近一次模型或工具事件 |
| `CANCELLED` | 已取消 | 谁发起了取消、工具是否及时停止 |
| `BUDGET_EXCEEDED` | 达到时间、Token 或工具次数限制 | `budgetExceededReason` 和实际用量 |
| `MAX_ITERATIONS_REACHED` | 模型调用次数达到上限 | 模型是否反复调用工具或无法形成答案 |
| `MAX_STEPS_REACHED` | 总执行步骤达到上限 | 是否出现过多重试、恢复或工具步骤 |

一个实用的排查顺序是：

```text
找到 turnId
    ↓
读取 AgentTurn 最新状态
    ↓
查看该任务最近的关键事件
    ↓
查看模型、工具和 HTTP Trace
    ↓
检查对应外部服务
```

先确定任务状态，可以大幅缩小日志和 Trace 的搜索范围。

## 日志应该记录什么

建议使用结构化日志，也就是每条日志使用固定字段，而不是只写一段无法搜索的自然语言：

```text
event=agent_turn_suspended
turnId=7d5b...
agentId=order-agent
agentVersion=2.1.0
status=WAITING_FOR_APPROVAL
toolName=refund_order
toolCallId=call-18
```

不同阶段可以关注以下信息：

| 阶段 | 建议记录 |
| --- | --- |
| 创建任务 | `turnId`、业务 `requestId`、`agentId`、`agentVersion` |
| 状态变化 | 事件类型、状态、步骤数、时间 |
| 工具调用 | `turnId`、`toolCallId`、工具名、耗时、成功或失败 |
| 自动重试 | 重试次数、失败类型、下次重试时间 |
| 等待外部操作 | 等待类型、关联 ID、开始等待时间 |
| 任务结束 | 最终状态、总耗时、模型次数、工具次数、Token 用量 |

日志中通常不应默认保存完整 Prompt、模型回答、工具参数、表单内容和审批备注。这些内容可能包含个人信息、
密钥或业务机密。确实需要临时调试时，也应进行脱敏、长度限制、访问控制并设置较短的保留时间。

## 指标应该观察什么

日志适合查询某一个任务，指标适合判断“整个系统最近是否正常”。可以从下面这些基础指标开始：

| 指标 | 可以发现的问题 |
| --- | --- |
| 任务创建、完成、失败和取消数量 | 总体任务量与异常变化 |
| 任务成功率 | 发布后是否出现大面积失败 |
| 任务总处理时间 | 用户整体等待时间是否变长 |
| 各等待状态的任务数和最长等待时间 | 审批、表单或外部工具是否积压 |
| 模型调用耗时和错误率 | 模型服务是否变慢或不可用 |
| 工具调用耗时和错误率 | 哪个业务工具出现故障 |
| 自动重试次数和重试后成功率 | 外部依赖是否持续不稳定 |
| Worker 待处理任务数和最老任务年龄 | 后台处理能力是否不足 |
| Token 使用量 | 成本和上下文是否异常增长 |

指标通常可以按 `agentId`、任务类型、工具名、模型名和部署环境分类。不要把 `turnId`、`requestId`、用户 ID
或订单号作为指标标签：这些值几乎每次都不同，会产生大量时间序列，增加监控系统的存储和查询压力。需要
定位单个任务时，把这些 ID 放在日志或 Trace 中。

## 用 Trace 定位慢调用

Trace 可以理解为“一次请求的完整调用路线”，Span 是路线中的一个步骤。一次报告任务可能显示为：

```text
生成销售报告
├── 调用模型                    1.8s
├── tool.query_sales           8.2s
│   └── http.client.request    8.0s  ERROR
└── 调用模型                    0.9s
```

从这棵调用树可以直接看出，主要时间花在查询销售数据的 HTTP 请求上，而不是模型生成答案。

Agents-Flex 的通用 Observability 模块可以自动记录 ChatModel、`ToolExecutor` 和框架 HTTP 客户端的 Span
与指标。`AgentRunner` 调用模型时，还会把当前 `turnId` 放入模型请求上下文，便于把模型 Span 与
AgentTurn 关联起来。

如果应用已经通过 Java Agent、Spring 或网关接入 OpenTelemetry，Agents-Flex 可以复用现有的全局配置。
如果还没有监控平台，可以先使用 Logging Exporter 在本地理解数据，再接入 Collector 或 APM。Trace、Span
和 Metric 的零基础说明见[可观测核心概念](../observability/concepts)。

## 实时进度与审计记录

实时页面通常通过 `AgentEventListener` 接收模型文字、工具进度和状态变化，再使用 WebSocket 或 SSE 推送给
浏览器。网络断线时事件可能丢失，因此页面重新连接后，应从 `AgentTurnStore` 查询最新状态。

合规审计与页面进度的要求不同。审批人、审批结果、关键工具执行和业务变更如果必须长期保留，应写入业务
自己的审计库或可靠消息系统。进程内事件不会在应用重启后自动重放，不能作为唯一审计记录。

可以简单理解为：

| 数据 | 适合回答的问题 |
| --- | --- |
| 任务快照 | 任务现在是什么状态 |
| 实时事件 | 页面此刻应该展示什么 |
| 业务审计记录 | 谁在什么时候做了什么 |
| Trace | 某一次调用慢在哪里、为什么失败 |
| 指标 | 整个系统最近是否正常 |

## 健康检查与告警

只检查应用 HTTP 接口是否存活还不够。接口正常，并不代表后台任务仍在运行。生产环境建议检查：

- `AgentTurnStore` 是否可以正常读写；
- `AgentLoader` 是否能加载当前版本和未完成任务使用的历史版本；
- Worker 最近一次成功轮询时间；
- 最老的待处理任务已经等待多久；
- 审批、表单和外部工具结果是否持续积压；
- 日志、指标和 Trace 的数据出口是否持续失败。

建议根据业务能够接受的等待时间设置告警，例如：

- 任务失败率超过业务阈值；
- 最老待处理任务超过服务承诺时间；
- Worker 超过多个轮询周期没有成功工作；
- `WAITING_FOR_TOOL` 或 `RETRY_SCHEDULED` 数量持续增长；
- Store 保存失败或 Agent 历史版本无法加载；
- 某个模型或工具的 P95 耗时明显升高。

P95 表示 95% 的调用都能在该时间内完成，比单纯看平均值更容易发现少量特别慢的请求。

## 数据安全

可观测数据会进入日志平台、APM 或数据库，应把这些系统视为独立的数据安全边界：

- 默认只记录状态、耗时、数量和必要的关联 ID；
- 不记录 API Key、访问令牌、Cookie 和密码；
- 对用户输入、模型输出、工具参数和工具结果进行脱敏；
- 限制单条日志和事件的大小；
- 按租户和角色控制查询权限；
- 为日志、Trace 和审计记录设置不同的保留周期。

通用 Observability 模块默认不采集模型响应、工具参数和工具结果：

```properties
agentsflex.otel.capture.content=false
```

即使显式开启内容采集，业务系统仍需判断哪些内容允许离开业务数据库。框架提供的字段脱敏不能替代权限
控制、数据分类和合规审查。

## 使用建议

- 把 `turnId` 作为排查 Agent 任务的首要关联 ID；
- 先查询当前状态，再查看事件、Trace 和外部服务日志；
- 关键事件日志使用固定字段，便于搜索和统计；
- 指标只使用数量有限的分类标签，单个任务 ID 放入日志或 Trace；
- 页面进度允许丢失并支持重新查询，关键审计必须可靠保存；
- 默认不记录 Prompt、模型输出和工具参数；
- 从少量真正有用的指标开始，根据实际故障逐步补充。

## 相关文档

- [AgentEventListener](./agent-event-listener)：监听任务状态、模型输出和工具进度
- [AgentTurn](./agent-turn)：查询任务状态、用量和最终结果
- [任务快照持久化](./store)：跨进程保存并查询任务进度
- [后台任务 Worker](./worker)：监控后台任务领取和故障接管
- [Observability 模块概述](../observability/observability)：了解模型、工具和 HTTP 自动观测
- [Observability 快速开始](../observability/getting-started)：把 Span 和指标输出到日志或监控平台
- [故障排查](../observability/troubleshooting)：排查数据没有产生或导出失败

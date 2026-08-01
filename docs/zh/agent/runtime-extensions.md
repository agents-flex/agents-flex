---
title: 运行上下文、Middleware 与上下文管理
description: 使用 AgentInvocationContext、AgentMiddleware、Command Inbox、上下文压缩和 Artifact Store 扩展 Agent 运行时。
---

# 运行上下文、Middleware 与上下文管理

<div v-pre>

## 能力边界

现代 Agent 运行时既要保存可恢复状态，也要接收只在当前请求中存在的身份、服务和连接。两类数据不能
混在一个 Map 中：

| 数据 | 生命周期 | 保存位置 |
| --- | --- | --- |
| 消息、阶段、审批结果、预算、待执行工具 | 跨进程恢复 | `AgentRunSnapshot` |
| 租户、用户、请求 ID、认证对象、进程内服务 | 当前调用 | `AgentInvocationContext` |
| Agent 的模型、工具、策略和 Middleware | Agent 定义版本 | `Agent` + Registry |
| 大型工具结果原文 | 独立内容生命周期 | `AgentArtifactStore` |
| 外部审批、输入和恢复动作 | 被 Worker 消费前持续存在 | `AgentRunCommandStore` |

## AgentInvocationContext

创建一次调用上下文：

```java
AgentInvocationContext context = AgentInvocationContext.builder()
    .tenantId("tenant-1001")
    .userId("user-2001")
    .sessionId("session-3001")
    .requestId("request-4001")
    .streaming(true)
    .attribute("channel", "console")
    .attribute(PermissionService.class, permissionService)
    .build();

AgentRun run = runner.run(
    agent,
    "查询并整理订单异常",
    AgentRunOptions.builder()
        .invocationContext(context)
        .metadata("taskType", "order-analysis")
        .build()
);
```

`metadata` 和 Invocation Context 的区别很重要：

- `metadata` 应保存需要审计、查询和恢复的可序列化业务字段；
- Invocation Context 可以保存 Java 服务对象，但任务恢复后不会自动存在；
- 不要把数据库连接、HTTP Client、认证对象或 Spring Bean 放进 Run metadata。

恢复时重新附加：

```java
AgentRun restored = runner.restore(runId, currentInvocationContext);
runner.runUntilBlocked(restored);
```

普通 Worker 没有请求上下文时会使用空 Context。平台可以在领取 Run 后根据 Snapshot metadata 构造新的
Invocation Context，再交给 Runner；认证和权限必须重新计算，不能盲目信任旧请求中的对象。

同一进程内由父 Run 创建的子 Run 会继承当前 Invocation Context，便于保持租户和请求追踪；子 Run
跨进程恢复时同样不会从 Snapshot 自动得到这些运行时对象。

## Agent Middleware

Middleware 有三条独立调用链：

```text
aroundStep
  └── aroundModelCall
        └── ChatModel

aroundStep
  └── aroundToolCall
        └── ToolInterceptor
              └── Tool
```

多个 Middleware 按注册顺序形成洋葱模型。前注册的实例先进入、后退出：

```java
AgentMiddleware authorization = new AgentMiddleware() {
    @Override
    public Object aroundToolCall(
        AgentToolCallContext context,
        AgentToolCallChain chain
    ) {
        PermissionService permissions = context.getInvocationContext()
            .get(PermissionService.class);
        permissions.check(context.getTool().getName());
        return chain.proceed(context);
    }
};

Agent agent = Agent.builder("order-agent")
    .middleware(authorization)
    .middleware(metricsMiddleware)
    .build();
```

Middleware 可以：

- 在模型调用前替换本次调用的 Prompt；
- 对模型或工具调用做缓存、限流、权限校验和降级；
- 直接返回结果实现短路；
- 读取 Invocation Context 和 Run 状态；
- 统一记录耗时、异常和业务维度。

`AgentListener` 仍然是观察接口。需要改变执行输入、输出或是否继续调用时使用 Middleware；只需要记录
生命周期时使用 Listener。

## ToolInterceptor 与 Middleware 的区别

| 扩展点 | 作用范围 | 适合处理 |
| --- | --- | --- |
| `AgentMiddleware.aroundToolCall` | 当前 Agent 的一次工具调用，能访问 Run 和 Invocation Context | Agent 权限、策略、任务级缓存 |
| `ToolInterceptor` | 通用 Tool 执行链，不依赖 Agent | 参数校验、工具 SDK 观测、通用重试 |
| `AgentToolProgressEmitter` | Tool 主动发出进度 | 百分比、当前阶段、已处理数量 |

Runner 会把 `AgentInvocationContext`、`AgentToolInvocation` 和 `AgentToolProgressEmitter` 一起放入
`ToolContext`。工具可以读取稳定幂等键，也可以实时报告进度。

## 持久化 Command Inbox

直接 `resume(...)` 适合同一请求立即继续执行。跨请求、跨服务或后台任务推荐使用 Inbox：

```java
AgentRunCommand saved = runner.submitCommand(
    "approval-request-9001",
    runId,
    AgentResumeCommand.approveTool(toolCallId)
        .withMetadata("approver", "alice")
);
```

`commandId` 是业务幂等键。相同 ID 和相同命令的重复提交返回已有记录；同一个 ID 被用于不同 Run、
不同审批决定或不同输入时会被拒绝。多个 Worker 通过命令 Lease 竞争领取。Runner
会在使 Run 可执行的同一个 Checkpoint 中写入 processed marker，因此以下崩溃窗口仍可恢复：

```text
领取 Command
  → 应用恢复动作
  → 保存 Run Checkpoint 和 processed marker
  → 进程退出，尚未 ack Command
  → Command Lease 到期后重新领取
  → 检测 marker，只 ack，不重复应用
```

命令连续处理失败三次后进入 `FAILED`，平台可以查询错误、人工修复后用新的 commandId 再次提交。

## 实时事件与唤醒

`AgentWakeupListener` 在命令持久化成功后调用：

```java
runner.addWakeupListener(command -> scheduler.wakeup(command.getRunId()));
```

它不是消息事实来源。即使通知丢失，Worker 的下一次轮询仍会从 Command Store 读取命令。

实时 UI 可以同时订阅：

```java
runner.addRuntimeEventListener(event -> {
    if (event.getType() == AgentRuntimeEventType.MODEL_TEXT_DELTA) {
        sse.send(event);
    }
});
```

Runtime Event 的 data 可以包含对象，不承诺跨进程序列化。需要审计的关键状态仍写入 `AgentRunEventStore`。

## 上下文压缩

按消息数量压缩旧历史：

```java
AgentContextManager contextManager = new MessageCountAgentContextManager(
    40,
    12,
    (messages, invocation) -> summaryModel.summarize(messages)
);

Agent agent = Agent.builder("analysis-agent")
    .contextManager(contextManager)
    .build();
```

当消息超过 40 条时，管理器把较早历史交给 summarizer，生成一条摘要消息，并保留最近 12 条。切分点会
避开 `AiMessage ToolCall → ToolMessage` 协议中间，避免模型看到没有对应 ToolCall 的工具结果。

压缩后的消息会立即 Checkpoint。恢复后的模型看到的是相同摘要，不会因为进程不同重复采用另一种压缩
结果。摘要模型本身的成本、超时和敏感信息处理由应用实现的 summarizer 负责。

## 大型工具结果外置

配置结果阈值：

```java
Agent agent = Agent.builder("data-agent")
    .toolResultOffloadPolicy(ToolResultOffloadPolicy.largerThan(32_000))
    .build();
```

超过阈值时：

```text
完整 Tool 结果
  → AgentArtifactStore.save(...)
  → AgentArtifactReference
  → ToolMessage 保存 artifactId / size / checksum
  → Checkpoint
```

`InMemoryAgentArtifactStore` 只适合测试和单进程应用。生产平台可以接入对象存储、文档库或自己的内容
服务，但应保证：

- artifactId 稳定且不可猜测；
- 按 tenantId、runId 和权限控制读取；
- 保存内容校验和与媒体类型；
- 定义 Run 删除后的保留策略；
- 日志和事件不直接输出完整大型结果。

框架不会自动把 Artifact 原文重新塞回 Prompt。模型需要读取完整内容时，应提供一个受控查询工具，按
artifactId 分页、检索或抽取所需片段，避免再次撑爆上下文。

## 生产 Store

当前核心模块提供内存实现和扩展接口。JDBC、Redis、对象存储等生产实现应由部署环境按一致性、容量和
运维要求选择。Run Store、Command Store、Event Store 和 Artifact Store 可以使用不同介质，但必须
明确各自的幂等、租约、事务和清理策略。

## 下一步

- [事件、监听器与审计](./events.md)
- [工具执行与审批](./tools-and-approval.md)
- [Worker 与 Lease](./worker-lease.md)
- [生产实践](./production.md)

</div>

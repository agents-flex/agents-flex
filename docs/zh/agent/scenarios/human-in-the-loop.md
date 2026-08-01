---
title: 场景：Human-in-the-loop
description: 以高风险工具审批为例，说明 Agent 如何暂停、持久化、审批并在任意 Runner 中恢复。
---

# 场景：Human-in-the-loop

<div v-pre>

## 场景目标

模型能够判断应该调用什么工具，但不应该自动获得所有业务操作权限。取消订单、发布生产版本、转账、删除数据等工具一旦执行就会产生真实副作用，因此需要把“模型提出操作”和“系统授权操作”拆开：

1. 模型生成原生 `ToolCall`；
2. Agent 根据审批策略进入等待状态；
3. 审批系统读取待审批内容并完成身份、权限和业务规则校验；
4. 审批结果以恢复命令写回；
5. Runner 从 Checkpoint 继续执行，而不是重新询问模型。

这个过程称为 Human-in-the-loop。这里的 Human 不一定只代表人工页面，也可以是规则引擎、风控服务或多级审批流。

## 业务示例

假设客服 Agent 可以查询订单，也可以取消订单：

| 工具 | 风险 | 策略 |
| --- | --- | --- |
| `get_order` | 只读 | 自动允许 |
| `cancel_order` | 修改订单状态 | 要求审批 |
| `refund_order` | 发生资金变更 | 由策略直接拒绝，交给专用退款系统 |

审批策略只决定工具调用是否可以继续，工具本身仍应做服务端鉴权、参数校验和幂等控制。

## 定义审批策略

```java
ToolApprovalPolicy approvalPolicy = (run, call, tool) -> {
    if ("refund_order".equals(call.getName())) {
        return ToolApprovalDecision.DENY;
    }
    if ("cancel_order".equals(call.getName())) {
        return ToolApprovalDecision.requireApproval()
            .code("HIGH_RISK_OPERATION")
            .message("高风险操作需要人工确认")
            .reason("工具具有外部副作用")
            .build();
    }
    return ToolApprovalDecision.ALLOW;
};

Agent agent = Agent.builder("order-agent")
    .id("order-agent")
    .version("1")
    .instructions("你是订单客服。查询订单可以直接执行；取消订单必须调用工具，不要声称已经取消。")
    .chatModel(chatModel)
    .tools(Arrays.asList(getOrderTool, cancelOrderTool, refundOrderTool))
    .toolApprovalPolicy(approvalPolicy)
    .build();
```

策略可以读取 `AgentRun` 的 metadata、工具名称和 ToolCall 参数，并结合租户、环境和操作金额作出决定。不要把最终授权只交给模型输出的自然语言。

## 第一次执行

```java
AgentRun run = runner.run(agent, "取消订单 A20260731001");

if (run.getStatus() == AgentRunStatus.WAITING_FOR_APPROVAL) {
    AgentSuspension suspension = run.getSuspension();
    System.out.println("runId = " + run.getId());
    System.out.println("callId = " + suspension.getCorrelationId());
    System.out.println("message = " + suspension.getMessage());
}
```

当策略返回 `REQUIRE_APPROVAL` 时：

- `cancel_order` 尚未执行；
- Run 状态变为 `WAITING_FOR_APPROVAL`；
- `AgentSuspension.correlationId` 保存 ToolCall ID；
- 待执行 ToolCall、消息、阶段和版本已经写入 `AgentRunStore`；
- `runUntilBlocked()` 返回调用方，不会占用线程等待审批。

因此审批可以持续几秒、几小时甚至几天，只要使用持久化 Store，进程重启也不会丢失任务。

## 审批接口设计

业务服务通常只向前端暴露自己的审批 API，不应直接暴露 `AgentRunner`：

```java
@PostMapping("/agent-runs/{runId}/tool-approval")
public ApprovalResponse approve(
        @PathVariable String runId,
        @RequestBody ApprovalRequest request,
        Authentication authentication) {

    AgentRun run = runner.restore(runId);
    requireApprovalPermission(authentication, run, request);

    AgentSuspension suspension = run.getSuspension();
    if (run.getStatus() != AgentRunStatus.WAITING_FOR_APPROVAL) {
        throw new IllegalStateException("当前任务不在工具审批状态");
    }
    if (!request.getCallId().equals(suspension.getCorrelationId())) {
        throw new IllegalArgumentException("审批请求与当前工具调用不匹配");
    }

    AgentResumeCommand command = request.isApproved()
        ? AgentResumeCommand.approveTool(request.getCallId())
        : AgentResumeCommand.rejectTool(request.getCallId(), request.getReason());
    command = command
        .withMetadata("approverId", currentUserId)
        .withMetadata("approvalSource", "order-console");

    AgentRunCommand saved = runner.submitCommand(
        request.getApprovalRequestId(),
        runId,
        command
    );
    return ApprovalResponse.accepted(saved);
}
```

接口层还应记录审批人、组织、来源 IP、审批意见和业务单号。这些信息适合写入业务审计表；也可以通过 `AgentResumeCommand.withMetadata(...)` 进入 Checkpoint，再由 Event Enricher 选择非敏感字段写入事件。

## `submitCommand()` 与 `resume()`

两种方法的差别在于恢复后是否由当前线程继续执行：

```java
// 先持久化审批命令，由 Worker 异步应用并继续执行。
runner.submitCommand(
    approvalRequestId,
    runId,
    AgentResumeCommand.approveTool(callId)
);

// 应用审批命令，并在当前线程执行到下一次阻塞或终止。
AgentRun result = runner.resume(
    runId,
    AgentResumeCommand.approveTool(callId)
);
```

生产环境通常推荐审批 API 调用 `submitCommand()`，随后由 `AgentWorker` 先消费 Command Inbox、再领取
Run。这样 HTTP 请求不需要承担模型调用和工具执行时间，也能承受服务重启、通知丢失和重复 Webhook。

本地程序、测试或低延迟同步接口可以直接调用 `resume()`。

## 批准后的执行

批准命令必须携带当前 ToolCall 的 `callId`。Runner 校验成功后会：

1. 清除当前 Suspension；
2. 恢复到保存的 `TOOLS` 阶段；
3. 执行原来已经持久化的工具调用；
4. 写入 `ToolMessage` 和新 Checkpoint；
5. 再次调用模型生成最终回答。

模型不会重新决定是否调用工具，因此不会因为提示变化产生另一份参数。

## 拒绝后的执行

```java
AgentRun result = runner.resume(
    runId,
    AgentResumeCommand.rejectTool(callId, "客户未确认取消费用")
);
```

拒绝不会执行工具。Runner 会把结构化拒绝结果作为 `ToolMessage` 交回模型，模型可以解释未执行原因、询问补充信息，或提供替代方案。

拒绝不等于 Run 必然失败。最终状态取决于模型接收到拒绝结果后的回答。

## 为什么必须校验 correlationId

同一个 Run 可能连续产生多个审批请求。用户打开旧页面后提交的迟到审批，不能误批准新的工具调用。

```text
审批页面读取 call-A
Run 因其他操作已经继续并等待 call-B
旧页面提交批准 call-A
Runner 发现 correlationId 不一致并拒绝恢复
```

业务接口和 Runner 都应校验 correlationId。前者给出友好的冲突响应，后者提供最终一致性保护。

## 跨进程恢复

Agent 定义中的模型、工具函数和拦截器不能直接序列化到 Checkpoint。恢复时依赖稳定 ID 重新解析：

```java
// 进程 A：创建并执行到审批等待状态
registryA.register(orderAgent);
AgentRunner runnerA = new AgentRunner(runStore, registryA, toolRegistry, eventStore);
AgentRun waiting = runnerA.run(orderAgent, input);

// 进程 B：注册相同 ID 的定义后恢复
registryB.register(orderAgent);
AgentRunner runnerB = new AgentRunner(runStore, registryB, toolRegistry, eventStore);
AgentRun completed = runnerB.resume(
    waiting.getId(),
    AgentResumeCommand.approveTool(callId)
);
```

所有进程必须共享 `AgentRunStore`，并能通过 `AgentRegistry` 解析相同的 `agentId + agentVersion`，通过 `AgentToolRegistry` 解析 Snapshot 中冻结的 `AgentToolReference`。

## 幂等与安全检查清单

- 工具调用以 `callId` 或业务操作号作为幂等键；
- 审批接口使用登录身份，不相信前端提交的用户或租户字段；
- 审批前重新校验订单当前状态和操作者权限；
- AgentRunStore 使用 version/CAS 防止两次审批同时覆盖；
- 工具服务自身继续执行鉴权，不把审批结果当作永久通行证；
- 拒绝已过期、已终止或 correlationId 不匹配的恢复命令；
- 记录审批人、理由、时间、ToolCall 参数摘要和最终工具结果；
- 敏感参数在事件、日志和 UI 中脱敏；
- 对生产发布、转账等操作设置时间和工具调用预算。

## 推荐的接口返回

审批接口不必返回完整消息历史，可以返回面向 UI 的状态视图：

```json
{
  "runId": "run-123",
  "status": "READY",
  "previousStatus": "WAITING_FOR_APPROVAL",
  "acceptedCallId": "call-456",
  "queued": true
}
```

前端随后通过状态查询或事件流观察 `TOOL_STARTED`、`TOOL_COMPLETED` 和 `RUN_COMPLETED`。

## 延伸阅读

- [工具执行与审批](../tools-and-approval.md)
- [Checkpoint 与中断恢复](../checkpoint-resume.md)
- [持久化工具审批 Demo](../demos/durable-tool-agent.md)
- [生产实践](../production.md)

</div>

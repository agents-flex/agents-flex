---
title: 人工审批
description: 在退款、删除、发布等高风险操作执行前加入人工确认，并根据审批结果继续任务。
---

# 人工审批

## 概述

人工审批用于保护会对真实业务产生影响的操作。例如，Agent 可以帮助用户整理退款信息，但不应仅根据
模型的判断直接完成退款；在实际扣款、退款、删除数据或发布内容之前，应由有权限的人员确认。

通过人工审批，可以将“提出操作”和“执行操作”分开：

1. Agent 根据用户要求选择需要调用的工具。
2. 审批规则判断该工具能否直接执行。
3. 高风险操作暂停执行，并向业务系统返回审批信息。
4. 审批人批准后继续执行；拒绝后不会执行该操作。

整个等待过程不会一直占用工作线程。业务系统可以在管理后台、工单页面或消息通知中完成审批，再继续
原来的任务。

人工审批只负责控制工具是否可以执行。用户登录、角色权限、审批额度和审计记录仍应由业务系统负责。

## 适用场景

建议对执行后难以撤销、涉及敏感数据或成本较高的操作启用审批。

| 场景 | 示例 | 审批时建议展示的信息 |
| --- | --- | --- |
| 资金操作 | 退款、转账、发放优惠券 | 金额、订单号、收款方 |
| 数据变更 | 删除记录、批量更新、清空数据 | 数据范围、记录数量、影响对象 |
| 对外操作 | 发布文章、发送短信、群发邮件 | 接收方、内容摘要、预计数量 |
| 生产环境操作 | 发布服务、修改配置、执行运维命令 | 环境、服务、版本、变更内容 |
| 高成本操作 | 大批量生成、付费接口调用 | 预计费用、资源数量、执行范围 |

普通查询、只读搜索和纯文本回答通常不需要审批。审批范围过大会增加等待时间，因此应优先保护真正有
风险的工具，而不是让所有操作都经过人工确认。

## 快速开始

下面以“退款订单”为例。目标是：查询类工具可以直接执行，`refund_order` 工具必须先获得人工批准。

示例中的 `chatModel` 表示已经创建好的大模型客户端，`refundService` 表示应用已有的退款服务。

### 1. 定义需要保护的工具

```java
Tool refundOrderTool = Tool.builder(
        "refund_order",                 // 工具名称，审批规则通过它识别退款操作
        "按照订单号发起退款")            // 工具用途，帮助模型正确选择工具
    .addParameter(Parameter.builder()
        .name("orderId")                // 参数名称
        .type("string")                 // 参数类型
        .required(true)                  // 订单号不能为空
        .build())
    .function(arguments -> {             // 只有审批通过后才会调用这里的业务代码
        String orderId = String.valueOf(arguments.get("orderId"));
        return refundService.refund(orderId);
    })
    .build();
```

这一段配置完成两件事：向模型说明“退款工具需要订单号”，并将工具调用连接到真实的退款服务。人工审批
必须在工具函数执行之前生效，否则退款已经发生，审批就失去了意义。

### 2. 为 Agent 配置审批规则

```java
Agent agent = Agent.builder("order-agent")
    .instructions("处理退款时必须调用 refund_order 工具，不得直接声称退款成功。")
    .chatModel(chatModel)                 // Agent 使用的大模型
    .tool(refundOrderTool)                // 注册退款工具，使模型可以选择它
    .toolApprovalPolicy((turn, call, tool) -> {
        // 只有退款工具需要人工审批，其他工具直接执行。
        if (!"refund_order".equals(tool.getName())) {
            return ToolApprovalDecision.ALLOW;
        }

        return ToolApprovalDecision.requireApproval()
            .code("REFUND_APPROVAL")
            .message("是否允许执行退款？")
            .reason("退款会修改订单和资金状态")
            .metadata("riskLevel", "HIGH")
            .build();
    })
    .build();
```

关键配置说明如下：

| 配置 | 作用 |
| --- | --- |
| `instructions(...)` | 告诉模型处理退款时必须使用工具，避免模型只生成一句“已退款” |
| `chatModel(...)` | 指定负责理解用户要求和选择工具的大模型 |
| `tool(...)` | 将退款工具注册到当前 Agent |
| `toolApprovalPolicy(...)` | 在工具执行前判断是直接执行、等待审批还是直接拒绝 |
| `tool.getName()` | 获取本次准备执行的工具名称，用于匹配审批规则 |
| `ToolApprovalDecision.ALLOW` | 允许工具立即执行，本例用于非退款工具 |
| `requireApproval()` | 要求人工审批，审批结果提交前不会执行工具函数 |
| `code(...)` | 稳定的业务代码，便于前端分类、统计和审计 |
| `message(...)` | 展示给审批人的简短问题 |
| `reason(...)` | 记录设置审批的原因，便于审计和问题排查 |
| `metadata(...)` | 附加风险等级等业务信息，值应当可以被序列化 |

如果没有配置 `toolApprovalPolicy(...)`，所有工具默认都可以直接执行。因此，只要 Agent 注册了退款、
删除或发布等高风险工具，就应显式配置审批规则。

### 3. 执行任务并读取审批请求

```java
AgentRunner runner = new AgentRunner();
AgentTurn waiting = runner.run(agent, "请退款订单 O-1001");

if (waiting.getStatus() == AgentTurnStatus.WAITING_FOR_APPROVAL) {
    String turnId = waiting.getId();
    String callId = waiting.getSuspension().getCorrelationId();
    String message = waiting.getSuspension().getMessage();

    // 将 turnId、callId、message 和经过脱敏的业务信息展示在审批页面。
}
```

`AgentRunner` 是任务执行器，`run(...)` 用于启动本次任务。当返回状态为
`WAITING_FOR_APPROVAL` 时，表示任务正在等待审批，退款服务尚未执行。

`new AgentRunner()` 使用进程内存保存任务，适合本地学习。生产环境中的审批通常跨越多个请求，应用重启
后也要能够继续，因此需要配置持久化存储，具体方式见 [任务快照持久化](./store)。

| 数据 | 用途 |
| --- | --- |
| `turnId` | 标识这一次 Agent 任务，提交审批结果时需要使用 |
| `callId` | 标识本次待审批的工具调用，防止审批结果作用到其他操作 |
| `message` | 审批规则中配置的展示文案 |

审批页面还可以展示订单号、退款金额等必要信息，但应由业务系统选择允许展示的字段并进行脱敏，不要将
模型生成的全部参数直接展示给审批人。

### 4. 提交批准或拒绝结果

批准后立即继续任务：

```java
AgentTurn result = runner.resume(
    turnId,
    AgentResumeCommand.approveTool(callId)
        .withMetadata("approverId", "user-1001")
);
```

拒绝时提供明确原因：

```java
AgentTurn result = runner.resume(
    turnId,
    AgentResumeCommand.rejectTool(callId, "退款金额超过当前审批额度")
        .withMetadata("approverId", "user-1001")
);
```

这里的配置含义如下：

| 配置 | 作用 |
| --- | --- |
| `resume(turnId, ...)` | 向指定任务提交审批结果，并在当前线程继续执行 |
| `approveTool(callId)` | 批准指定工具调用；随后才会执行退款工具 |
| `rejectTool(callId, reason)` | 拒绝指定工具调用；退款工具不会执行，原因会交给 Agent 处理 |
| `withMetadata(...)` | 附加审批人、审批来源等信息，为后续审计提供上下文 |

批准或拒绝都必须使用本次审批请求返回的 `turnId` 和 `callId`，不要重新创建一个 Agent 任务。

## 工具中的审批信息

多数工具只需要在审批通过后正常执行业务操作，不必关心任务之前是否等待过审批。少数情况下，工具还需要：

- 使用稳定的幂等键，避免退款、转账或发布操作被重复执行；
- 在业务日志中关联当前任务和工具调用；
- 读取审批命令附带的审批人、审批渠道等信息。

这时可以在工具函数中读取 `AgentToolContext`。它是 Runner 执行当前本地工具时提供的只读运行信息：

```java
import com.agentsflex.agent.tool.AgentToolContext;
import com.agentsflex.agent.tool.AgentToolResumeInfo;

Tool refundOrderTool = Tool.builder(
        "refund_order",
        "按照订单号发起退款")
    .addParameter(Parameter.builder()
        .name("orderId")
        .type("string")
        .required(true)
        .build())
    .function(arguments -> {
        AgentToolContext context = AgentToolContext.current();
        AgentToolResumeInfo resumeInfo = context.getResumeInfo();

        String orderId = String.valueOf(arguments.get("orderId"));
        String idempotencyKey = context.getIdempotencyKey();
        Object approverId = resumeInfo.getMetadata().get("approverId");

        return refundService.refund(
            orderId, idempotencyKey, approverId);
    })
    .build();
```

示例中的三参数 `refund(...)` 代表业务系统自己的退款方法。重点是把框架提供的稳定幂等键和必要的审批信息
传给业务服务，而不是要求业务服务使用这个固定的方法签名。

`AgentToolResumeInfo` 不需要业务代码自行创建，它可以通过 `context.getResumeInfo()` 读取。审批通过后的工具
执行具有以下特点：

| 方法 | 审批通过后返回的含义 |
| --- | --- |
| `isApprovalResumed()` | `true`，表示本次执行来自审批通过 |
| `isResumed()` | `true`，表示任务曾经暂停并恢复 |
| `isReplay()` | `false`，因为审批发生在工具函数执行之前 |
| `getExecutionAttempt()` | `1`，这是工具函数第一次真正开始执行 |
| `getResumeInfo().getMetadata()` | 包含审批规则和审批命令附带的业务信息 |

如果审批被拒绝，工具函数不会执行，因此也不会进入工具内部读取这些信息。审批 metadata 适合日志关联和
业务审计，但不能替代审批接口本身的身份、权限和额度校验。

## 审批规则

审批规则可以根据工具名称、工具元数据、任务上下文或调用参数决定如何处理。规则支持三种结果：

| 结果 | 含义 | 典型用途 |
| --- | --- | --- |
| `ALLOW` | 立即执行工具 | 普通查询、低风险操作 |
| `REQUIRE_APPROVAL` | 等待人工批准或拒绝 | 退款、删除、发布等高风险操作 |
| `DENY` | 直接禁止执行，不进入人工审批 | 系统明确不允许的操作 |

`DENY` 与审批人点击“拒绝”含义不同：`DENY` 是系统规则直接禁止操作，`rejectTool(...)` 是人工查看请求
后作出的拒绝决定。

### 按工具元数据统一配置

当高风险工具较多时，可以在工具上标记风险属性，再使用同一条规则处理：

```java
Tool deleteDataTool = Tool.builder("delete_data", "删除指定范围的数据")
    .metadata("requiresApproval", true)
    .function(arguments -> dataService.delete(arguments))
    .build();

Agent agent = Agent.builder("data-agent")
    .instructions("执行数据操作前确认范围，不得虚构执行结果。")
    .chatModel(chatModel)
    .tool(deleteDataTool)
    .toolApprovalPolicy((turn, call, tool) ->
        Boolean.TRUE.equals(tool.getMetadata().get("requiresApproval"))
            ? ToolApprovalDecision.requireApproval()
                .code("HIGH_RISK_TOOL")
                .message("该操作需要人工审批")
                .reason("工具被标记为高风险操作")
                .build()
            : ToolApprovalDecision.ALLOW)
    .build();
```

审批规则应只读取信息并返回决定，不应在规则中调用退款、删除等业务接口。规则还应保持结果稳定，避免
同一组条件在短时间内得到不同的审批要求。

## 同步与异步处理

`resume(...)` 会在提交审批结果的当前线程中继续任务，适合本地程序或执行时间较短的接口。

在 Web 应用中，审批接口通常需要尽快返回，可以改用：

```java
runner.submitResume(
    turnId,
    AgentResumeCommand.approveTool(callId)
        .withMetadata("approverId", "user-1001")
);
```

`submitResume(...)` 只提交审批结果，不在当前请求中继续调用模型或执行工具。之后需要由已配置的
`AgentWorker` 在后台继续任务。后台执行方式请参考 [Worker](./worker)。

| 方式 | 提交后是否立即继续任务 | 适用场景 |
| --- | --- | --- |
| `resume(...)` | 是 | 命令行程序、内部服务、短任务 |
| `submitResume(...)` | 否 | Web 审批接口、后台任务、长任务 |

## 审批页面与业务记录

一个基本的审批页面应当展示：

- 操作名称和风险说明；
- 订单、数据范围或发布版本等关键业务信息；
- 发起人、发起时间和当前状态；
- 批准与拒绝操作；
- 拒绝原因输入框。

业务系统应单独保存完整的审批记录，至少包括 `turnId`、`callId`、审批结果、审批人、审批时间和拒绝
原因。框架返回的任务状态可以用于展示当前进度，但不能代替合规审计记录。

如果应用配置了聊天记录，还可以把审批请求展示在对话时间线中；这属于页面集成能力，不影响审批接口
本身。更完整的跨请求示例见 [Demo：人工审批](./demo-human-approval)。

## 安全要求

1. 审批接口必须校验登录用户、租户和审批权限，不能只凭 `turnId` 或 `callId` 放行。
2. 审批额度必须由业务系统校验。例如，主管只能批准 1 万元以内的退款。
3. 页面只展示审批所需字段，并对手机号、银行卡号等敏感数据脱敏。
4. 重复点击或重复回调只能产生一次有效结果，业务系统应为审批请求设置唯一标识。
5. 退款、转账、发布等工具本身也应支持防重复执行，不能只依赖审批按钮防重。
6. 人工批准不代表参数一定正确，工具执行前仍要进行业务校验。

## 相关文档

- 完整的跨请求审批示例：[Demo：人工审批](./demo-human-approval)
- 了解任务等待后如何继续：[挂起和恢复](./suspend-resume)
- 了解后台任务处理：[Worker](./worker)
- 了解生产环境的任务保存方式：[任务快照持久化](./store)
- 了解如何为 Agent 注册工具：[Agent](./agent#工具配置)
- 了解工具中的幂等键和恢复信息：[AgentToolContext](./tool-context)

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

## AgentApprovalRequiredException

### 概述

`AgentApprovalRequiredException` 是本地 Tool 主动申请人工审批的控制流异常。它适用于 Tool 必须先完成
只读查询，才能判断是否需要审批，或者才能生成金额、影响范围、目标对象等审批展示信息的场景。

普通本地 Tool 无需声明额外属性。Tool 在产生任何副作用之前构造一个 `REQUIRE_APPROVAL` 类型的
`ToolApprovalDecision`，并抛出 `AgentApprovalRequiredException`。Runner 捕获后会保留当前 ToolCall，
创建标准 `TOOL_APPROVAL` 挂起点，并等待业务系统批准或拒绝。

审批次数并不固定。一次 ToolCall 可以根据运行时结果：

- 不抛异常，直接完成，不产生 Tool 主动审批；
- 抛出一次异常，完成一次审批；
- 依次抛出多个具有不同请求指纹的异常，完成多次或多级审批。

### 作用

`AgentApprovalRequiredException` 主要解决以下问题：

1. **运行时判断**：审批条件依赖实时余额、订单状态、数据规模或只读预检结果，无法在 Tool 执行前静态确定。
2. **动态展示信息**：审批页面需要展示查询后才能获得的金额、版本、收款方或影响范围。
3. **复用统一协议**：继续使用已有的挂起、审批事件、超时、恢复、持久化和审计机制，不建立第二套审批接口。
4. **绑定业务快照**：通过请求指纹把批准结果绑定到本次预检数据；金额、目标对象或版本变化时，旧批准不会被误用。
5. **支持多次申请**：同一 ToolCall 可以依次申请财务、合规等不同批准，已经通过的请求不会被后续请求覆盖。

它是 `toolApprovalPolicy` 的补充，不是替代。`toolApprovalPolicy` 仍是默认且强制的中央安全边界，适合
执行前即可判断的静态规则、统一治理以及外部 Tool。Tool 内抛出的异常只能增加审批要求，不能撤销或绕过
中央策略的拒绝和审批。

### 适用场景

| 场景 | 为什么需要在 Tool 内申请审批 | 建议放入审批信息的内容 |
| --- | --- | --- |
| 大额退款或付款 | 必须查询订单、余额或实时额度后才能判断 | 订单号、金额、币种、收款方 |
| 批量数据变更 | 必须先统计实际影响范围 | 记录数量、筛选条件、数据版本 |
| 生产环境变更 | 必须读取当前部署状态后生成变更计划 | 环境、当前版本、目标版本、影响服务 |
| 条件式对外发送 | 必须先计算真实接收范围或费用 | 接收人数、内容摘要、预计费用 |
| 多角色审批 | 后一级审批信息依赖前一级批准后重新执行的预检 | 审批角色、业务快照、风险原因 |

如果风险仅由 Tool 名称、参数或静态元数据决定，应直接使用 `toolApprovalPolicy`。如果流程可以自然拆成
“只读预检 Tool”和“产生副作用的 Tool”，也应优先让后者由 `toolApprovalPolicy` 保护。外部 Tool 在
Runner 进程中没有本地函数可以抛出异常，因此只能使用 `toolApprovalPolicy` 在分发前审批。

### 执行流程

1. Runner 先执行 `toolApprovalPolicy`；返回 `DENY` 或 `REQUIRE_APPROVAL` 时不会进入 Tool。
2. 中央策略返回 `ALLOW` 后，本地 Tool 执行可重复的只读预检。
3. Tool 根据预检结果直接继续，或者抛出 `AgentApprovalRequiredException`。
4. Runner 创建审批挂起点；批准后从头重新执行原 ToolCall，拒绝后终止该 ToolCall。
5. Tool 重新构造审批请求，并通过 `AgentToolContext.isToolApproved(request)` 判断同一请求是否已经批准。
6. 如果还有其他审批请求，Tool 可以再次抛出异常；全部请求通过后才能产生副作用。

### 示例代码

```java
import com.agentsflex.agent.exception.AgentApprovalRequiredException;
import com.agentsflex.agent.tool.AgentToolContext;
import com.agentsflex.agent.tool.ToolApprovalDecision;

Tool refundOrderTool = Tool.builder("refund_order", "按照订单号发起退款")
    .function(arguments -> {
        AgentToolContext context = AgentToolContext.current();
        String orderId = String.valueOf(arguments.get("orderId"));

        // 这里只允许执行可重复的只读预检。恢复后整个函数会从头执行，因此查询也会再次发生。
        RefundPreview preview = refundService.preview(orderId);

        ToolApprovalDecision request = ToolApprovalDecision.requireApproval()
            .code("LARGE_REFUND")
            .message("是否允许执行大额退款？")
            .reason("退款金额超过自动处理额度")
            .metadata("orderId", orderId)
            .metadata("amount", preview.getAmount())
            .build();
        if (preview.getAmount() > AUTO_APPROVAL_LIMIT
            && !context.isToolApproved(request)) {
            // 普通本地 Tool 不需要添加额外 metadata，可直接抛出审批异常。
            throw new AgentApprovalRequiredException(request);
        }

        // 所有写入、扣款和外部发送必须位于审批判断之后，并使用稳定幂等键防止重复生效。
        return refundService.refund(
            orderId, context.getIdempotencyKey());
    })
    .build();
```

`AgentApprovalRequiredException` 只接受 `REQUIRE_APPROVAL` 决定。它携带的 `code`、`message`、
`reason` 和 `metadata` 会进入现有
`AgentSuspension`、审批事件及审计记录，不需要为 Tool 主动审批建立另一套提交接口。
批准和拒绝仍分别使用 `AgentResumeCommand.approveTool(callId)` 与
`AgentResumeCommand.rejectTool(callId, reason)`。

`AgentToolSuspensionException` 是审批与表单输入共用的抽象控制流基类，只表达“当前 Tool 需要暂停”，
不持有 `ToolApprovalDecision`，也不能直接实例化。审批必须抛出 `AgentApprovalRequiredException`；表单
输入必须抛出 [`AgentFormRequiredException`](./form-input#agentformrequiredexception)。这样 Runner 可以按
具体协议类型分派，不需要用 null 值判断暂停原因。

### 恢复语义

Tool 主动审批发生时 Tool 函数已经开始运行。批准后 Java 调用栈不会从 `throw` 的下一行继续，而是从函数开头
重新执行，因此它和中央策略审批的执行次数语义不同：

| 信息 | 中央策略审批通过 | Tool 主动审批通过 |
| --- | --- | --- |
| Tool 在审批前是否进入 | 否 | 是，只允许只读预检 |
| `isPolicyApproved()` | `true` | 取决于中央策略是否曾要求人工审批 |
| `isToolApproved(request)` | `false` | 指纹匹配时为 `true` |
| `isApprovalResumed()` | `true` | `true` |
| `isReplay()` | `false` | `true` |
| 首次获批执行的 `getExecutionAttempt()` | `1` | `2` |

Tool 主动申请审批时必须使用 `isToolApproved(request)`，不要只使用 `isApprovalResumed()`。
Runner 会为 `code + message + reason + 排序后的 metadata` 生成稳定 SHA-256
指纹；恢复后金额、目标对象或业务版本变化时，旧批准自动失效并重新挂起。业务已有版本号、ETag 时，
也可以用 `requestFingerprint(version)` 显式绑定。`isApprovalResumed()` 只表示最近一次恢复来自审批；
后续表单或重试不会丢失已经累计的 Tool 审批记录。

中央策略审批与 Tool 主动审批分别保存为 `ToolApprovalStage.POLICY` 和 `ToolApprovalStage.TOOL`，同一
ToolCall 可以同时拥有一条中央策略记录和多条按请求指纹隔离的 Tool 主动审批记录。旧 Snapshot 中的
`toolApprovals` 布尔值只按 `POLICY` 解释，不会被提升为 Tool 主动审批的批准记录。

### 多级批准

同一个 ToolCall 可以按顺序申请多个不同批准。例如付款操作可以先经过财务审批，再经过合规审批：

```java
ToolApprovalDecision financeRequest = ToolApprovalDecision.requireApproval()
    .code("FINANCE_APPROVAL")
    .message("财务是否批准本次付款？")
    .metadata("amount", preview.getAmount())
    .build();
if (!context.isToolApproved(financeRequest)) {
    throw new AgentApprovalRequiredException(financeRequest);
}

ToolApprovalDecision complianceRequest = ToolApprovalDecision.requireApproval()
    .code("COMPLIANCE_APPROVAL")
    .message("合规是否批准向该收款方付款？")
    .metadata("payee", preview.getPayeeId())
    .build();
if (!context.isToolApproved(complianceRequest)) {
    throw new AgentApprovalRequiredException(complianceRequest);
}

// 两个请求均已批准后才允许产生副作用。
return paymentService.release(context.getIdempotencyKey(), preview);
```

第一次批准后 Tool 从头执行，通过财务检查并在合规检查处再次挂起。第二次批准后 Tool 再次从头执行；
此时两个请求的批准都能按各自指纹查到，因此可以继续完成付款。相关读取 API 如下：

| API | 含义 |
| --- | --- |
| `isToolApproved(request)` | 指定请求是否已经批准，业务授权判断必须使用它 |
| `getToolApprovalRecord(request)` | 指定请求的完整决定、审批人及响应 metadata |
| `getToolApprovalRecords()` | 当前 ToolCall 全部 Tool 主动审批记录，按请求指纹索引 |
| `getToolApprovalRecord()` | 最近一次 Tool 主动审批决定，仅用于展示和兼容，不适合作为授权判断 |

多级批准一次只能暴露一个待处理请求，因为一个 Turn 同一时刻只有一个 Suspension。任一级被拒绝后，
当前 ToolCall 都不会再次进入 Tool，也不会执行后续副作用。自动生成的指纹包含审批代码、说明、原因和
metadata；如果显式设置 `requestFingerprint(...)`，每个独立审批级别必须使用不同且稳定的值，不能让
财务审批和合规审批共用同一指纹。

### 适用限制

- 能在 Tool 执行前确定风险时，继续使用 `toolApprovalPolicy`。
- 能拆分流程时，优先拆成“只读预检 Tool”和“受中央策略保护的副作用 Tool”。
- 只有实时额度、查询结果或运行时生成的展示内容决定审批请求时，才在本地 Tool 内抛异常。
- 异常之前不得写数据库、扣费、发消息或调用外部写接口；只读预检也应支持重复执行。
- 任意普通本地 Tool 都可以直接抛出 `AgentApprovalRequiredException`，不需要额外元数据声明。
- 顺序模式下，异常会立即挂起，后续 Tool 不会开始。并行模式下，同批 Tool 已经提交后无法可靠撤销；
  Runner 会保存其他已完成结果，再挂起当前 Tool。因此 Tool 主动审批只保护当前 Tool 自己位于异常后的副作用。
- 如果审批必须阻止整个并行批次启动，应使用执行前的 `toolApprovalPolicy`，或把相关 Tool 配置为顺序执行。
- 审批 metadata 会递归复制和冻结；其中仍应只放可序列化、非敏感的业务快照字段。
- `toolApprovalPolicy` 抛异常时 Runner 按普通执行失败/重试处理并保持 fail-closed，不会默认放行。
- Middleware/Interceptor 即使包装控制流异常，Runner 也会沿 cause 链识别审批和表单请求。

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

`submitResume(...)` 只提交审批结果，不在当前请求中继续调用模型或执行工具。之后需要由业务线程、消息队列或调度器显式调用 `runner.run(turnId)` 继续任务。

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
- 了解生产环境的任务保存方式：[任务快照持久化](./store)
- 了解如何为 Agent 注册工具：[Agent](./agent#工具配置)
- 了解工具中的幂等键和恢复信息：[AgentToolContext](./tool-context)

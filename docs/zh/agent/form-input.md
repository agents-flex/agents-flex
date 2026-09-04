---
title: 表单输入
description: 在 Agent 任务缺少必要信息时展示结构化表单，并在用户提交后继续原任务。
---

# 表单输入

## 概述

用户提出任务时，经常不会一次提供所有必要信息。例如：

- 预定会议室时没有说明会议时间和参会人数；
- 创建故障工单时缺少受影响系统和影响范围；
- 申请报销时没有填写费用类型、金额或发票信息；
- 预约服务时没有选择日期、地点和联系方式。

对于简单问题，Agent 可以直接在对话中追问。但当需要收集多个字段、限制可选值或检查必填项时，使用
表单通常更加清晰。页面可以将字段显示为输入框、下拉框或数字输入框，用户提交完整信息后，Agent 再
继续原来的任务。

表单输入主要解决三个问题：

1. 明确告诉用户还需要填写哪些信息。
2. 使用固定字段和选项，减少格式错误与理解歧义。
3. 在用户填写期间保存当前任务，提交后从原任务继续。

表单只负责收集信息，不代表相关业务操作已经执行。例如，填写退款申请不等于退款成功；涉及资金、
删除或发布等高风险操作时，还应根据业务要求配置[人工审批](./human-approval)。

## 适用场景

| 场景 | 适合收集的字段 | 推荐控件 |
| --- | --- | --- |
| 会议室预定 | 主题、时间、参会人数 | 文本框、日期时间、数字输入框 |
| 故障工单 | 受影响系统、影响范围、错误描述 | 文本框、下拉框、多行文本框 |
| 报销申请 | 费用类型、金额、发生日期 | 下拉框、金额输入框、日期选择器 |
| 服务预约 | 服务类型、预约时间、联系方式 | 下拉框、日期时间、文本框 |

如果只缺少一个简单值，并且不要求固定格式，普通对话追问可能更自然。需要多个字段、明确选项或前后端
统一校验时，再使用表单。

## 快速开始

下面以“预定会议室”为例。用户只说“帮我预定会议室”时，Agent 会先请求用户填写会议主题、期望时间
和参会人数，收到表单后再调用会议室预定工具。

示例中的 `chatModel` 表示已经创建好的大模型客户端，`meetingService` 表示应用已有的会议室服务。

### 1. 定义表单字段

表单使用 JSON Schema 描述。下面通过 Java `Map` 定义三个字段：

```java
Map<String, Object> subject = new LinkedHashMap<>();
subject.put("type", "string");
subject.put("title", "会议主题");

Map<String, Object> preferredTime = new LinkedHashMap<>();
preferredTime.put("type", "string");
preferredTime.put("title", "期望时间");
preferredTime.put("description", "例如：明天下午 3 点");

Map<String, Object> participantCount = new LinkedHashMap<>();
participantCount.put("type", "integer");
participantCount.put("title", "参会人数");
participantCount.put("minimum", 1);

Map<String, Object> properties = new LinkedHashMap<>();
properties.put("subject", subject);
properties.put("preferredTime", preferredTime);
properties.put("participantCount", participantCount);

Map<String, Object> meetingSchema = new LinkedHashMap<>();
meetingSchema.put("type", "object");
meetingSchema.put("title", "填写会议安排");
meetingSchema.put("properties", properties);
meetingSchema.put("required", Arrays.asList(
    "subject", "preferredTime", "participantCount"));
```

常用的 Schema 配置如下：

| 配置 | 作用 |
| --- | --- |
| `type: object` | 表示整份表单由多个字段组成 |
| `properties` | 定义表单包含哪些字段 |
| 字段的 `type` | 定义数据类型，例如 `string`、`integer` 或 `boolean` |
| 字段的 `title` | 页面上展示的字段名称 |
| 字段的 `description` | 补充填写提示 |
| `required` | 列出必须填写的字段 |
| `minimum` | 设置数字字段的最小值，本例要求参会人数至少为 1 |

需要提供固定选项时，可以使用 `enum`：

```java
impactScope.put("enum", Arrays.asList(
    "ONE_USER", "PARTIAL_USERS", "ALL_USERS"));
```

前端可以据此显示下拉框，后端也应使用同样的选项校验提交值。

### 2. 创建并注册表单

```java
AgentFormDefinition meetingForm = AgentFormDefinition
    .builder("meeting_room_booking")
    .description("预定会议室时收集会议主题、时间和参会人数")
    .schema(meetingSchema)
    .build();

Tool userInputTool = AgentUserInputTool.builder()
    .form(meetingForm)
    .build();
```

| 配置 | 作用 |
| --- | --- |
| `builder("meeting_room_booking")` | 设置表单的稳定标识；同一个 Agent 中不能重复 |
| `description(...)` | 说明表单适用于什么情况，帮助 Agent 选择正确表单 |
| `schema(...)` | 指定表单字段、数据类型和校验规则 |
| `AgentUserInputTool.builder()` | 创建框架提供的表单请求工具 |
| `form(meetingForm)` | 将表单注册到该工具；也可以连续注册多份表单 |

`AgentUserInputTool` 不会执行会议室预定。它只允许 Agent 在缺少信息时请求一份已经注册的表单。表单的
字段结构由应用提前定义，模型不能临时增加或修改字段。

### 3. 定义后续业务工具

用户提交表单后，Agent 需要调用真正的会议室预定工具：

```java
Tool reserveMeetingRoom = Tool.builder(
        "reserve_meeting_room",
        "根据完整的会议资料预定会议室")
    .addParameter(Parameter.builder()
        .name("subject")
        .type("string")
        .description("会议主题")
        .required(true)
        .build())
    .addParameter(Parameter.builder()
        .name("preferredTime")
        .type("string")
        .description("会议时间")
        .required(true)
        .build())
    .addParameter(Parameter.builder()
        .name("participantCount")
        .type("integer")
        .description("参会人数")
        .required(true)
        .build())
    .function(arguments -> meetingService.reserve(arguments))
    .build();
```

表单负责收集数据，`reserve_meeting_room` 负责执行业务操作。两者分开后，即使用户尚未填写完整信息，
预定服务也不会被提前调用。

### 4. 创建 Agent

```java
Agent agent = Agent.builder("meeting-agent")
    .instructions(
        "用户要求预定会议室但信息不完整时，调用 request_user_input，"
            + "并选择 meeting_room_booking。收到表单数据后，"
            + "必须调用 reserve_meeting_room，不得直接声称预定成功。")
    .chatModel(chatModel)
    .tool(userInputTool)
    .tool(reserveMeetingRoom)
    .build();
```

| 配置 | 作用 |
| --- | --- |
| `instructions(...)` | 告诉 Agent 何时请求表单，以及收到数据后应执行什么操作 |
| `chatModel(...)` | 指定负责理解用户要求和选择工具的大模型 |
| `tool(userInputTool)` | 让 Agent 可以请求用户填写表单 |
| `tool(reserveMeetingRoom)` | 让 Agent 可以在资料完整后执行会议室预定 |

指令中应明确说明“不能直接声称成功”。模型生成的文字不是业务执行结果，真正的预定结果必须来自业务
工具。

### 5. 执行任务并读取表单

```java
AgentRunner runner = new AgentRunner();
AgentTurn waiting = runner.run(agent, "帮我预定一个会议室");

if (waiting.getStatus() == AgentTurnStatus.WAITING_FOR_USER) {
    String turnId = waiting.getId();
    String inputId = waiting.getSuspension().getCorrelationId();
    String formKey = waiting.getSuspension().getFormKey();
    Map<String, Object> schema = waiting.getSuspension().getSchema();

    // 将 turnId、inputId、formKey 和 schema 返回给前端。
}
```

`AgentRunner` 是任务执行器。当状态为 `WAITING_FOR_USER` 时，表示当前任务正在等待用户填写信息，后续
业务工具尚未执行。

| 数据 | 用途 |
| --- | --- |
| `turnId` | 标识当前任务，提交表单时需要使用 |
| `inputId` | 标识当前输入请求，防止数据提交到其他表单 |
| `formKey` | 标识页面正在填写哪一种业务表单 |
| `schema` | 描述页面字段和校验规则，前端可据此渲染表单 |

`new AgentRunner()` 使用进程内存保存任务，只适合本地学习。生产环境中的表单通常跨越多个请求，需要
配置持久化存储，具体方式见 [任务快照持久化](./store)。

### 6. 提交表单并继续任务

用户填写表单后，将数据整理为与 Schema 字段一致的 `Map`：

```java
Map<String, Object> formData = new LinkedHashMap<>();
formData.put("subject", "项目周会");
formData.put("preferredTime", "明天下午 3 点");
formData.put("participantCount", 8);

AgentTurn result = runner.resume(
    turnId,
    AgentResumeCommand.userInput(inputId, formData)
        .withMetadata("submittedBy", "user-1001")
);
```

| 配置 | 作用 |
| --- | --- |
| `formData` | 保存用户填写的字段和值，字段名必须与 Schema 一致 |
| `userInput(inputId, formData)` | 将数据提交给对应的输入请求 |
| `resume(turnId, ...)` | 提交数据后，在当前线程继续原任务 |
| `withMetadata(...)` | 附加提交人、提交渠道等信息，为后续审计提供上下文 |

提交前，后端必须检查用户身份、字段类型、必填项、允许值和业务权限。前端校验主要用于改善填写体验，
不能代替后端校验。

## 注册多份表单

一个 Agent 可以处理多种输入场景。例如，同时支持会议室预定和故障工单：

```java
Tool userInputTool = AgentUserInputTool.builder()
    .form(meetingForm)
    .form(supportTicketForm)
    .build();
```

每份表单必须使用不同的 `formKey`，并提供清楚、不重叠的 `description`。Agent 会根据这些说明选择表单，
因此不应使用“表单一”“其他信息”之类含义不明确的描述。

还应在 Agent 指令中写清楚各表单的使用条件：

```java
.instructions(
    "预定会议室缺少资料时选择 meeting_room_booking；"
        + "创建故障工单缺少资料时选择 support_ticket_details；"
        + "不得猜测用户没有提供的字段。")
```

## 同步与异步提交

`resume(...)` 会在提交表单的当前线程中继续任务，适合命令行程序、内部服务或执行时间较短的接口。

Web 表单接口通常需要尽快返回，可以使用：

```java
runner.submitResume(
    turnId,
    AgentResumeCommand.userInput(inputId, formData)
        .withMetadata("submittedBy", "user-1001")
        .withMetadata("requestId", "request-789")
);
```

`submitResume(...)` 只提交数据，不在当前请求中继续调用模型或执行业务工具。之后需要由已配置的
`AgentWorker` 在后台继续任务，具体方式见 [Worker](./worker)。

| 方式 | 提交后是否立即继续任务 | 适用场景 |
| --- | --- | --- |
| `resume(...)` | 是 | 本地程序、内部服务、短任务 |
| `submitResume(...)` | 否 | Web 接口、后台任务、长任务 |

## 前端渲染

前端可以根据标准 JSON Schema 选择控件：

| Schema 配置 | 常见页面控件 |
| --- | --- |
| `type: string` | 文本输入框 |
| `type: string` 加 `enum` | 下拉框或单选框 |
| `type: integer` / `number` | 数字输入框 |
| `type: boolean` | 复选框或开关 |
| `description` | 字段下方的填写提示 |
| `required` | 必填标记与提交校验 |

如果已经配置聊天记录，框架还可以通过 `AgentFormMessage` 提供待填写、已提交或已取消等页面状态。它是
一种可选的页面集成方式；只使用等待任务返回的 `schema`、`turnId` 和 `inputId` 也可以实现表单页面。

页面不应允许用户修改 Schema，也不应根据 Schema 执行 HTML、JavaScript 或其他表达式。提交成功后应
禁用重复提交，并以服务端返回的任务状态为准。

## 进阶：由业务工具请求表单

多数场景推荐使用前面的方式：让 Agent 在执行业务工具之前发现信息不足并请求表单。

少数情况下，只有查询业务规则后才能确定需要补充哪些字段。例如，系统读取客户类型后，才知道还需要
企业税号还是个人证件信息。这时可以在业务工具中请求表单：

```java
import com.agentsflex.agent.exception.AgentFormRequiredException;
import com.agentsflex.agent.tool.AgentToolContext;
import com.agentsflex.agent.tool.AgentToolResumeInfo;

Tool prepareCustomerTool = Tool.builder(
        "prepare_customer",
        "检查客户资料并整理开户所需信息")
    .function(arguments -> {
        AgentToolContext context = AgentToolContext.current();
        Map<String, Object> submitted = context.getSubmittedFormData();

        if (submitted.isEmpty()) {
            throw new AgentFormRequiredException(customerDetailsForm);
        }

        return customerService.prepare(arguments, submitted);
    })
    .build();
```

这种方式需要特别注意：用户提交后，工具函数会从头再次执行，而不是从抛出异常的下一行继续。因此，
请求表单必须发生在写数据库、扣费或调用外部写入接口之前。更稳妥的做法是让该工具只负责查询和整理
资料，再由另一个工具执行真正的业务写入。

### 读取工具的表单恢复信息

上面示例中的 `AgentToolContext` 不只提供表单数据，还能说明当前工具是否因为表单提交而再次执行：

```java
AgentToolContext context = AgentToolContext.current();
AgentToolResumeInfo resumeInfo = context.getResumeInfo();

if (context.isFormInputResumed()) {
    Map<String, Object> submitted =
        context.getSubmittedFormData();
    int resumeCount = resumeInfo.getResumeCount();
    int executionAttempt = context.getExecutionAttempt();

    // 校验 submitted，并记录本次恢复和执行次数。
}
```

第一次进入工具函数时，尚未提交表单：

| 信息 | 第一次执行 | 用户提交后的执行 |
| --- | --- | --- |
| `getSubmittedFormData()` | 空 Map | 返回已经提交并合并的字段 |
| `isFormInputResumed()` | `false` | `true` |
| `isReplay()` | `false` | `true` |
| `getExecutionAttempt()` | `1` | 从 `2` 开始递增 |
| `getResumeInfo().getType()` | `NONE` | `FORM_INPUT` |
| `getResumeInfo().getResumeCount()` | `0` | 每次恢复后递增 |

`AgentToolResumeInfo` 是 Runner 为当前工具准备的只读恢复说明，业务代码不需要自行创建。多次请求表单时，
`getSubmittedFormData()` 会合并此前提交的字段，同名字段使用最近一次提交的值。

这组信息只适用于“业务工具抛出 `AgentFormRequiredException`”的方式。前文使用的
`request_user_input` 会先把表单数据交回模型，再由模型调用后续业务工具；后续工具是一次新的工具调用，
不会把这份数据放入它的 `getSubmittedFormData()`。后续工具应从模型生成的工具参数中读取所需字段。

| 方式 | 适用条件 | 推荐程度 |
| --- | --- | --- |
| Agent 主动请求已注册表单 | 执行前就能判断缺少哪些信息 | 默认选择 |
| 业务工具运行时请求表单 | 必须查询业务规则后才能确定字段 | 仅在确有需要时使用 |

关于工具恢复后的上下文和防重复执行方式，请参考 [工具运行上下文](./tool-context)。

## 表单输入与人工审批

表单输入和人工审批可能连续出现，但解决的问题不同：

| 能力 | 解决的问题 | 示例 |
| --- | --- | --- |
| 表单输入 | 执行任务所需的信息不完整 | 补充退款原因和收款账户 |
| 人工审批 | 信息已经完整，但操作需要获得授权 | 主管确认是否允许退款 |

一个退款流程可以先让用户补全资料，再生成明确的退款操作，最后交给有权限的人员审批。不能用“用户已
填写表单”代替操作授权，也不能用审批页面代替字段校验。

## 安全要求

1. 后端必须重新校验字段类型、必填项、长度、范围和枚举值，不能只依赖前端校验。
2. 提交接口必须校验登录用户、租户、任务状态和 `inputId`。
3. Schema 与表单数据中不要包含密码、密钥等不应长期保存的敏感信息。
4. 重复点击或重复回调只能产生一次有效提交，业务系统应使用 `requestId` 等唯一标识防重。
5. 页面只渲染受信任的控件，不执行 Schema 中的脚本、HTML 或表达式。
6. 表单提交成功不代表业务操作成功，最终结果应以业务工具的真实返回值为准。

## 相关文档

- 了解任务等待后如何继续：[挂起和恢复](./suspend-resume)
- 为高风险操作增加授权确认：[人工审批](./human-approval)
- 了解后台任务处理：[Worker](./worker)
- 了解生产环境的任务保存方式：[任务快照持久化](./store)
- 了解工具的调用身份和恢复信息：[AgentToolContext](./tool-context)

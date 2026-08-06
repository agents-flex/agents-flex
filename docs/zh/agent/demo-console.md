---
title: Demo：完整示例（控制台程序）
description: 使用真实模型构建持续对话、任务规划、表单输入、工具调用、人工审批和实时事件控制台。
---

# Demo：完整示例（控制台程序）

## 概述

控制台 Demo 使用真实 OpenAI-compatible ChatModel，展示一个完整交互应用：普通持续对话、按需任务
规划、只读工具自动执行、工具动态请求表单、有副作用工具人工审批、阻塞 Turn 恢复、每轮独立 Turn、
业务 ChatMemory 以及实时事件输出。

源码位于 `demos/agent-console-demo/src/main/java/com/agentsflex/demo/agent/console/AgentConsoleDemo.java`。

## 功能结构

程序包含三个业务工具和一个控制工具：

- `get_current_time`：无副作用，Runner 直接执行。
- `request_user_input`：模型主动选择 `meeting_request` 表单，Runner 接管 ToolCall 并等待用户提交。
- `prepare_support_ticket`：没有副作用；首次执行抛出 `AgentFormRequiredException` 请求故障详情表单，
  提交后从 Snapshot 读取表单数据并重新执行。
- `create_support_ticket`：会写业务系统，审批策略要求人工确认。

控制台业务代码维护 conversationId 和 `ChatMemory`，Runner 分页读取模型可见历史并增量写回本轮消息；
审批输入按 turnId 恢复原 Turn，不创建新 Turn，也不会清空或整体重写 ChatMemory。

## 配置模型

模型连接全部从环境变量读取：

```bash
export AGENT_DEMO_API_KEY="your-api-key"
export AGENT_DEMO_MODEL="gpt-4o-mini"
```

默认使用 `https://api.openai.com/v1/chat/completions`。

使用其他 OpenAI-compatible 服务时：

```bash
export AGENT_DEMO_ENDPOINT="https://your-provider.example.com"
export AGENT_DEMO_REQUEST_PATH="/v1/chat/completions"
export AGENT_DEMO_MODEL="your-tool-calling-model"
```

也兼容 `OPENAI_API_KEY`。所选模型必须支持 Tool Calling。

## 核心 Agent 配置

```java
Agent agent = Agent.builder("console-assistant")
    .id("console-assistant")
    .version("1")
    .instructions(
        "结合完整会话历史理解用户请求。"
        + "当前时间必须调用 get_current_time。"
        + "收集会议安排时调用 request_user_input 并选择 meeting_request。"
        + "创建工单时必须先调用 prepare_support_ticket 补齐资料，"
        + "准备完成后再调用 create_support_ticket。"
        + "需要两个或更多独立工具调用时必须先创建计划。")
    .chatModel(chatModel)
    .tool(currentTime)
    .tool(AgentUserInputTool.builder().form(meetingRequestForm).build())
    .tool(prepareTicket)
    .tool(createTicket)
    .maxAttachedMessages(40)
    .planningPolicy(AgentPlanningPolicy.builder()
        .enabled(true)
        .maxTasks(4)
        .maxDepth(1)
        .childPlanningAllowed(false)
        .taskResultMaxLength(2_000)
        .planningInstructions(
            "两个或更多独立工具调用必须规划；每个任务只完成一个独立动作，"
            + "比较和最终结论由父 Agent 汇总。")
        .build())
    .toolApprovalPolicy((turn, call, tool) ->
        Boolean.TRUE.equals(tool.getMetadata().get("sideEffect"))
            ? ToolApprovalDecision.requireApproval()
                .code("CONSOLE_WRITE_APPROVAL")
                .message("该工具会写入支持系统，需要人工确认")
                .build()
            : ToolApprovalDecision.ALLOW)
    .executionPolicy(AgentExecutionPolicy.builder()
        .maxIterations(8)
        .budget(AgentBudget.builder()
            .maxToolCalls(8)
            .maxTotalTokens(100_000)
            .maxDurationMillis(120_000)
            .build())
        .build())
    .build();
```

工具 metadata 只提供策略事实；真正的执行授权由审批策略决定。

## 表单输入示例

Demo 同时展示两种表单入口。模型主动入口先注册稳定表单：

```java
AgentFormDefinition meetingRequestForm = AgentFormDefinition
    .builder("meeting_request")
    .description("用户要求收集或确认会议主题、时间和参会人数时使用")
    .schema(meetingRequestSchema)
    .build();

Tool userInputTool = AgentUserInputTool.builder()
    .form(meetingRequestForm)
    .build();
```

模型只能看到 `meeting_request` 和 `description`，调用 `request_user_input` 后由 Runner 保存 Schema、
暂停 Turn，并投影 `AgentFormMessage`。提交数据会成为该控制 ToolCall 的 ToolMessage，然后返回 MODEL
阶段让模型汇总。

另一种入口由业务工具在执行过程中动态请求表单：

```java
AgentFormDefinition ticketDetailsForm = AgentFormDefinition
    .builder("support_ticket_details")
    .description("准备故障工单时缺少受影响系统或影响范围")
    .schema(ticketDetailsSchema)
    .build();

Tool prepareTicket = Tool.builder(
        "prepare_support_ticket", "整理创建支持工单所需的完整资料")
    .function(arguments -> {
        AgentToolContext context = AgentToolContext.current();
        Map<String, Object> submitted = context.getSubmittedFormData();
        if (submitted.isEmpty()) {
            throw new AgentFormRequiredException(ticketDetailsForm);
        }
        Map<String, Object> prepared = new LinkedHashMap<>(arguments);
        prepared.putAll(submitted);
        return prepared;
    })
    .build();
```

第一次执行时，Runner 捕获异常并进入 `WAITING_FOR_USER`，同时把 Schema 投影为
`AgentFormMessage`。控制台读取 Suspension 中的同一份 Schema，根据 `properties`、`required`、
`enum` 和字段类型逐项收集数据，然后提交：

```java
runner.resume(
    turnId,
    AgentResumeCommand.userInput(callId, formData)
        .withMetadata("submittedBy", "console-user"));
```

提交数据进入 Snapshot，原 `prepare_support_ticket` 从函数开头重新执行，并通过
`getSubmittedFormData()` 读取数据。准备工具完成后，模型调用真正的 `create_support_ticket`，再进入
人工审批。表单中断发生在副作用之前，并使用稳定 ToolCall ID 保持恢复和幂等语义。

规划能力是同一个 Agent 的能力，不需要额外的 planning 入口。框架本身仍允许模型判断是否需要规划；
为了让控制台示例可以稳定复现，Demo 的指令明确要求包含两个或更多独立工具调用的请求必须先调用内置
`create_task_plan` 工具。每个任务创建独立子 Turn，当前 Demo 允许委派给自身，但禁止子 Turn 再次规划，
并把单个子任务结果限制在 2000 字符以内。比较、归纳和最终结论留给父 Agent，不创建额外总结任务。

## ChatMemory 与单轮业务信息

```java
String conversationId = "console-" + UUID.randomUUID();
ChatMemory memory = new DefaultChatMemory(conversationId);

AgentTurnOptions options = AgentTurnOptions.builder()
    .metadata("requestId", UUID.randomUUID().toString())
    .metadata("userId", "console-user")
    .build();

ChatMemoryProvider memoryProvider = id -> memory;

AgentRunner runner = AgentRunner.builder()
    .turnStore(turnStore)
    .agentLoader(agentLoader)
    .chatMemoryProvider(memoryProvider)
    .build();

AgentTurn turn = runner.run(agent.getId(), conversationId, new UserMessage(input), options);
```

ChatMemory 管理跨轮完整时间线；Runner 通过 Provider 读取模型消息，并在 Snapshot 保存后幂等投影本轮
消息。页面可以使用 `getMessages(50)` 读取最近时间线，模型视图由 Runner 调用
`getModelMessages(maxAttachedMessages)` 获取。metadata 只需保存当前 Turn 恢复后仍需使用的其他业务标识。

## 处理阻塞状态

```java
while (turn.getStatus().isBlocked()) {
    if (turn.getStatus() == AgentTurnStatus.WAITING_FOR_APPROVAL) {
        String callId = turn.getSuspension().getCorrelationId();
        turn = runner.resume(turn.getId(),
            approved
                ? AgentResumeCommand.approveTool(callId)
                : AgentResumeCommand.rejectTool(callId, "用户拒绝"));
        continue;
    }
    if (turn.getStatus() == AgentTurnStatus.WAITING_FOR_USER) {
        AgentSuspension suspension = turn.getSuspension();
        Map<String, Object> schema =
            (Map<String, Object>) suspension.getMetadata().get("schema");
        Map<String, Object> formData = renderAndReadForm(schema);
        turn = runner.resume(turn.getId(), AgentResumeCommand.userInput(
            suspension.getCorrelationId(), formData));
        continue;
    }
    break;
}
```

子 Agent 和重试通常由 Worker 处理，控制台不会盲目继续这些状态。

## 实时事件

```java
runner.addEventListener(event -> {
    switch (event.getType()) {
        case MODEL_STARTED:
        case TOOL_STARTED:
        case TOOL_COMPLETED:
        case TOOL_APPROVAL_REQUESTED:
        case TOOL_INPUT_REQUESTED:
        case PLAN_CREATED:
        case TASK_STARTED:
        case TASK_COMPLETED:
        case TASK_FAILED:
        case TURN_SUSPENDED:
        case TURN_RESUMED:
            System.out.println("[事件] " + event.getType());
            break;
        default:
            break;
    }
});
```

真实 Web 应把相同事件映射为 SSE/WebSocket，并在断线重连时重新读取 Turn 当前状态。

## 构建与运行

```bash
mvn -pl demos/agent-console-demo -am install -DskipTests
mvn -f demos/agent-console-demo/pom.xml exec:java
```

进入控制台后依次尝试：

```text
你好，我叫小明。
你还记得我叫什么吗？
上海现在几点？
请帮我收集会议安排信息。
帮我创建一个高优先级登录故障工单。
分别查询上海和东京当前时间，并比较时差给出会议建议。
```

命令 `/history` 查看最近 50 条时间线消息，`/help` 查看帮助，`/exit` 退出。会议信息示例由模型直接
触发 `request_user_input`；创建工单示例则由准备工具抛出异常请求表单。创建工单时会先展示
JSON Schema 表单，依次填写受影响系统、影响范围和可选错误提示；提交后准备工具会重新执行，随后
创建工具展示完整 ToolCall 参数并等待明确批准或拒绝。最后一条时间查询示例会展示计划创建、子任务
开始和完成事件，以及最终计划状态。规划子 Turn 中发生交互等待时，控制台仍使用根 turnId 恢复，
Runner 自动把命令路由到实际子 Turn。
无法识别的审批输入不会自动当作拒绝。

当前 Demo 的 Turn Store 和 ChatMemory 都使用进程内实现，程序退出后状态会丢失。

## 从 Demo 到生产

控制台使用内存 Store，退出后状态丢失。生产服务应把 Runner、共享 Store、AgentLoader 和 Worker 作为应用级组件；将审批输入改为带鉴权的 HTTP API，并由业务 Inbox 或消息队列保证可靠性后调用 `submitResume`；持久化 conversationId、ChatMemory 和活动 turnId；对输出与工具参数脱敏；并配置网络超时、业务幂等、指标和审计保留策略。

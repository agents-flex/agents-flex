---
title: Demo：完整示例（控制台程序）
description: 使用真实模型构建持续对话、工具调用、人工审批和实时事件控制台。
---

# Demo：完整示例（控制台程序）

## 概述

控制台 Demo 使用真实 OpenAI-compatible ChatModel，展示一个完整交互应用：普通持续对话、只读工具自动执行、有副作用工具人工审批、阻塞 Run 恢复、每轮独立 Run、共享 Conversation Memory 以及实时事件输出。

源码位于 `demos/agent-console-demo/src/main/java/com/agentsflex/demo/agent/console/AgentConsoleDemo.java`。

## 功能结构

程序包含两个工具：

- `get_current_time`：无副作用，Runner 直接执行。
- `create_support_ticket`：会写业务系统，审批策略要求人工确认。

同一个 `AgentConversation` 保存控制台用户的历史，每条普通输入创建新的 Run；审批输入恢复 active Run，不创建新 Run。

## 配置模型

模型连接全部从环境变量读取：

```bash
export AGENT_DEMO_API_KEY="your-api-key"
export AGENT_DEMO_MODEL="gpt-4o-mini"
```

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
        + "只有用户明确要求创建工单时才调用 create_support_ticket。")
    .chatModel(chatModel)
    .tool(currentTime)
    .tool(createTicket)
    .toolApprovalPolicy((run, call, tool) ->
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

## Conversation 与单轮 Context

```java
AgentConversation conversation = AgentConversation.create(
    "console-" + UUID.randomUUID(), agent);

AgentRunOptions options = AgentRunOptions.builder()
    .invocationContext(AgentInvocationContext.builder()
        .sessionId(conversation.getId())
        .requestId(UUID.randomUUID().toString())
        .userId("console-user")
        .build())
    .build();

AgentRun run = runner.run(conversation, new UserMessage(input), options);
```

Conversation 管消息历史；Invocation Context 管本轮身份与追踪信息。两者不能互相替代。

## 处理阻塞状态

```java
while (run.getStatus().isBlocked()) {
    if (run.getStatus() == AgentRunStatus.WAITING_FOR_APPROVAL) {
        String callId = run.getSuspension().getCorrelationId();
        run = runner.resume(conversation,
            approved
                ? AgentResumeCommand.approveTool(callId)
                : AgentResumeCommand.rejectTool(callId, "用户拒绝"));
        continue;
    }
    if (run.getStatus() == AgentRunStatus.WAITING_FOR_USER) {
        run = runner.resume(conversation,
            AgentResumeCommand.userInput(additionalInput));
        continue;
    }
    break;
}
```

子 Agent 和重试通常由 Worker 处理，控制台不会盲目继续这些状态。

## 实时事件

```java
runner.addRuntimeEventListener(event -> {
    switch (event.getType()) {
        case MODEL_STARTED:
        case TOOL_STARTED:
        case TOOL_COMPLETED:
        case TOOL_APPROVAL_REQUESTED:
        case RUN_SUSPENDED:
        case RUN_RESUMED:
            System.out.println("[事件] " + event.getType());
            break;
        default:
            break;
    }
});
```

真实 Web 应把相同事件映射为 SSE/WebSocket，并在断线重连时重新读取 Run 当前状态。

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
帮我创建一个高优先级登录故障工单。
```

命令 `/history` 查看协议消息，`/help` 查看帮助，`/exit` 退出。最后一条会展示 ToolCall 参数并等待批准或拒绝。

## 从 Demo 到生产

控制台使用内存 Store，退出后状态丢失。生产服务应把 Runner、共享 Store、AgentLoader 和 Worker 作为应用级组件；将审批输入改为带鉴权的 HTTP API 与 Command Inbox；持久化 Conversation；对输出与工具参数脱敏；并配置网络超时、业务幂等、指标和审计保留策略。

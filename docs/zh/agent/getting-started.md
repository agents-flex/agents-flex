---
title: Agent 快速开始
description: 添加依赖，创建 Agent 和 Tool，并运行第一个完整 Tool Calling 循环。
---

# Agent 快速开始

## 概述

本节用最小代码完成一次“模型决定调用工具，Runner 执行工具，模型根据结果回答”的闭环。示例在当前线程运行，使用内存 Store，适合先理解 API；审批、恢复和 Worker 会在后续章节逐步加入。

## 添加依赖

在 Maven 项目中引入 Agent 模块和一个 ChatModel 实现。版本应与项目其他 Agents-Flex 模块保持一致。

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-agent</artifactId>
    <version>${agents-flex.version}</version>
</dependency>

<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-chat-openai</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

`agents-flex-agent` 已依赖 `agents-flex-core`。如果使用 Qwen、Ollama 或其他模型，将第二个依赖替换为对应实现即可。

## 创建 ChatModel

下面使用 OpenAI-compatible 服务。密钥不要硬编码到源码中。

```java
ChatModel chatModel = OpenAIChatConfig.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .endpoint("https://api.openai.com")
    .requestPath("/v1/chat/completions")
    .model("gpt-4.1-mini")
    .supportTool(true)
    .buildModel();
```

模型实现必须支持 Tool Calling，且工具名和参数 Schema 会随 Prompt 发送给模型。

## 定义工具

```java
Tool weather = Tool.builder("get_weather", "查询城市的实时天气")
    .addParameter(Parameter.builder()
        .name("city")
        .type("string")
        .description("城市名称")
        .required(true)
        .build())
    .function(args -> {
        String city = String.valueOf(args.get("city"));
        return city + "：晴，26℃";
    })
    .build();
```

工具名称在一个 Agent 内必须唯一。描述和参数应说明“何时调用”与“需要什么值”，因为模型依据这些信息生成 `ToolCall`。

## 创建 Agent

```java
Agent agent = Agent.builder("weather-assistant")
    .id("weather-assistant")
    .version("1")
    .description("回答天气问题")
    .instructions("回答天气问题时必须先调用 get_weather，不要猜测实时天气。")
    .chatModel(chatModel)
    .tool(weather)
    .build();
```

`id` 和 `version` 是恢复协议的一部分。即使本地示例可以只设置名称，准备持久化时也应显式设置稳定 ID 和版本。

## 运行任务

```java
AgentRunner runner = new AgentRunner();
AgentRun run = runner.run(agent, "上海今天天气如何？");

if (run.getStatus() == AgentRunStatus.COMPLETED) {
    System.out.println(run.getFinalOutput());
} else {
    System.out.println("status=" + run.getStatus());
}
```

`run(...)` 会先创建 Run，再在当前线程持续推进，直到完成、失败或进入阻塞状态。模型可能被调用不止一次：第一次产生 ToolCall，工具执行后第二次生成面向用户的答案。

## 查看运行信息

```java
System.out.println("runId=" + run.getId());
System.out.println("iterations=" + run.getIterationCount());
System.out.println("steps=" + run.getStepCount());
System.out.println("toolCalls=" + run.getToolCallCount());
System.out.println("tokens=" + run.getTotalTokens());
```

`iterationCount` 统计模型调用次数，`stepCount` 统计 Runner 的总推进次数，工具调用次数由 `toolCallCount` 单独记录。

## 设置运行限制

```java
AgentExecutionPolicy policy = AgentExecutionPolicy.builder()
    .maxIterations(8)
    .maxSteps(32)
    .budget(AgentBudget.builder()
        .maxToolCalls(10)
        .maxTotalTokens(20_000)
        .maxDurationMillis(60_000)
        .build())
    .build();

Agent limited = Agent.builder("weather-assistant")
    .chatModel(chatModel)
    .tool(weather)
    .executionPolicy(policy)
    .build();
```

也可以用 `AgentRunOptions.executionPolicy(...)` 只覆盖某一次运行，通过 `metadata(...)` 附加可持久化业务信息，并用 `streaming(...)` 控制当前进程内的模型调用方式。

## 持续对话

一个 Run 对应一个任务，不应把已完成 Run 直接当成下一轮对话。持续对话的 ID 和 `ChatMemory` 由业务系统维护，每轮把历史消息传给 Runner：

```java
ChatMemory memory = loadMemory("session-1001");
AgentRun first = runner.run(agent,
    memory.getMessages(Integer.MAX_VALUE), new UserMessage("我叫小明"));
saveHistory(memory, first.getConversationHistory());

AgentRun second = runner.run(agent,
    memory.getMessages(Integer.MAX_VALUE), new UserMessage("我叫什么？"));
```

每一轮创建新的 `AgentRun`。业务系统负责回写 `getConversationHistory()`、防止同一会话并发开始两轮，并保存尚未结束的 runId。如果某轮处于阻塞状态，应按该 runId 恢复原 Run，而不是开始新一轮。

## 下一步

建议依次阅读 [Agent](./agent)、[AgentRunner](./agent-runner) 和 [AgentRun](./agent-run)，建立定义、执行器与运行状态的清晰边界，然后再接入[挂起和恢复](./suspend-resume)及[Store 持久化](./store)。

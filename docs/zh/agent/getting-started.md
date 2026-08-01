---
title: Agent 快速开发
description: 从 Maven 依赖、真实 ChatModel 和 Tool 开始，完成一次可持续对话的 Agent 调用。
---

# Agent 快速开发

## 概述

本页完成一个可以直接回答问题、调用实时时间工具并持续对话的 Agent。示例只使用进程内 Store，适合先理解 API；它不包含分布式 Worker 和持久化数据库。

## 快速开发

下面从依赖、模型、工具和 Agent 定义开始，完成第一条消息，再把同一个 Runner 扩展为持续对话和多模态输入。

## 添加依赖

Agent 运行时与具体模型适配器是独立模块。使用 OpenAI-compatible 模型时加入：

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

`agents-flex-agent` 负责运行状态与调度，`agents-flex-chat-openai` 提供 ChatModel。使用其他模型时替换第二个依赖即可。

## 创建 ChatModel

```java
ChatModel chatModel = OpenAIChatConfig.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .endpoint("https://api.openai.com")
    .requestPath("/v1/chat/completions")
    .model("gpt-4.1-mini")
    .supportTool(true)
    .buildModel();
```

Agent 要调用工具，模型适配器必须启用并支持原生 ToolCall。endpoint、requestPath 和模型名应按实际服务调整。

## 定义第一个 Tool

```java
Tool currentTime = Tool.builder("get_current_time", "查询指定时区的当前时间")
    .addParameter(Parameter.builder()
        .name("zoneId")
        .type("string")
        .description("IANA 时区，例如 Asia/Shanghai")
        .required(true)
        .build())
    .metadata("sideEffect", false)
    .function(arguments -> ZonedDateTime.now(
        ZoneId.of(String.valueOf(arguments.get("zoneId")))).toString())
    .build();
```

工具名必须在一个 Agent 内唯一。描述和参数 Schema 是模型选择工具、生成参数的依据；Java 函数仍应自己校验参数和业务权限。

## 定义 Agent

```java
Agent agent = Agent.builder("assistant")
    .id("assistant")
    .version("1")
    .description("回答常见问题并查询实时时间")
    .instructions(
        "你是中文助手。普通问候直接回答；询问当前时间时必须调用 get_current_time。")
    .chatModel(chatModel)
    .tool(currentTime)
    .build();
```

`id` 用于加载和恢复，`version` 让已创建的 Run 能绑定到同一套定义。生产环境应显式设置二者。

## 执行一条消息

```java
AgentRunner runner = AgentRunner.builder()
    .agentLoader(new InMemoryAgentLoader(agent))
    .build();

AgentRun run = runner.run(agent, "上海现在几点？");

if (run.getStatus() == AgentRunStatus.COMPLETED) {
    System.out.println(run.getFinalOutput());
} else {
    System.out.println(run.getStatus());
}
```

`run()` 会在当前线程执行到完成、失败、预算终止或等待外部事件。不要只读取最终文本，也应检查状态。

## 持续对话

```java
AgentConversation conversation = AgentConversation.create("user-42", agent);

AgentRun first = runner.run(conversation, "你好，我在上海");
AgentRun second = runner.run(conversation, "那我这里现在几点？");
```

这里产生两个独立 Run，但共享 Conversation 的 Memory。第二轮能够理解“这里”指上海；第一轮和第二轮仍分别拥有自己的状态、Token、事件和 Checkpoint。

## 多模态消息

```java
UserMessage message = new UserMessage("请分析这张图片中的异常");
message.addImageUrl(imageUrl);

AgentRun run = runner.run(conversation, message);
```

`UserMessage` 还可以添加音频、视频和文件 URL。最终能否理解对应内容取决于所选模型及模型适配器能力。

## 添加运行身份

租户、用户和请求 ID 不应写进系统指令。使用 `AgentInvocationContext`：

```java
AgentRunOptions options = AgentRunOptions.builder()
    .invocationContext(AgentInvocationContext.builder()
        .tenantId("tenant-a")
        .userId("user-42")
        .requestId("req-1001")
        .build())
    .metadata("taskType", "realtime-query")
    .build();

AgentRun run = runner.run(agent, new UserMessage("查询时间"), options);
```

Invocation Context 只在当前调用链存在；metadata 会进入 Snapshot，适合后续查询和审计。

## 下一步

如果工具会修改外部状态，应继续阅读 [Tool 与审批](./tool.md)。任务可能超过接口超时时间时，使用 [Worker 与分布式执行](./worker.md)。完整可运行代码位于[示例与 Demo](./examples.md)。

---
title: Agent 快速开始
description: 从 ChatModel、Tool 和 Agent 定义开始，运行第一个模型原生工具调用智能体。
---

# Agent 快速开始

<div v-pre>

## 准备工作

Agent 核心 API 位于 `agents-flex-core`。实际调用模型时还需要一个模型实现，例如 OpenAI 兼容对话模块：

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-chat-openai</artifactId>
    <version>2.2.6</version>
</dependency>
```

该模块会传递依赖 `agents-flex-core`。请通过环境变量提供密钥：

```bash
export AI_API_KEY="your-api-key"
export AI_MODEL="gpt-4o"
```

## 第一个工具 Agent

下面创建一个天气查询工具，让模型自行判断何时调用。

```java
import com.agentsflex.core.agent.Agent;
import com.agentsflex.core.agent.AgentRun;
import com.agentsflex.core.agent.AgentRunner;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.model.chat.openai.OpenAIChatConfig;

public class WeatherAgentDemo {

    public static void main(String[] args) {
        ChatModel chatModel = OpenAIChatConfig.builder()
            .apiKey(System.getenv("AI_API_KEY"))
            .model(System.getenv("AI_MODEL"))
            .buildModel();

        Tool weather = Tool.builder("get_weather", "查询指定城市的天气")
            .addParameter(Parameter.builder()
                .name("city")
                .type("string")
                .description("城市名称")
                .required(true)
                .build())
            .function(arguments -> {
                String city = String.valueOf(arguments.get("city"));
                return city + "：晴，24°C";
            })
            .build();

        Agent agent = Agent.builder("weather-agent")
            .instructions("你是天气助手。需要实时天气时调用工具，不要编造数据。")
            .chatModel(chatModel)
            .tool(weather)
            .build();

        AgentRun run = new AgentRunner().run(agent, "今天杭州天气怎么样？");

        System.out.println("status = " + run.getStatus());
        System.out.println("answer = " + run.getFinalOutput());
    }
}
```

典型执行过程：

```text
1. AgentRunner 创建并保存 AgentRun
2. 模型返回 get_weather ToolCall
3. Runner 执行天气工具
4. Runner 写入 ToolMessage 和 Checkpoint
5. 模型读取工具结果并返回最终回答
6. Run 进入 COMPLETED
```

## 最重要的三个对象

### Agent：能力定义

`Agent` 是不可变定义，可以被多个请求复用：

```java
Agent agent = Agent.builder("weather-agent")
    .id("weather-agent")
    .version("1")
    .instructions("你是天气助手")
    .chatModel(chatModel)
    .chatOptions(chatOptions)
    .tools(tools)
    .executionPolicy(executionPolicy)
    .build();
```

`id` 用于 Checkpoint 恢复，发布后不要随意改变。

### AgentRun：一次运行

每个用户目标都创建独立 `AgentRun`：

```java
AgentRun run = runner.start(agent, "查询杭州天气");
```

Run 保存消息、状态、阶段、待执行工具、Token、重试、暂停和最终结果。通过 Runner 创建会立即保存初始 Checkpoint；不要在多个并发线程中同时推进同一个 Run。

### AgentRunner：执行器

最简单的同步调用：

```java
AgentRun completed = runner.run(agent, input);
```

需要控制执行边界时：

```java
AgentRun run = runner.start(agent, input);

while (!run.getStatus().isTerminal() && !run.getStatus().isBlocked()) {
    runner.step(run);
}
```

通常优先使用：

```java
runner.runUntilBlocked(run);
```

它会持续推进，直到完成、失败、预算耗尽，或者等待审批、用户、子 Agent 和重试时间。

## 读取结果与状态

```java
if (run.getStatus() == AgentRunStatus.COMPLETED) {
    System.out.println(run.getFinalOutput());
} else if (run.getStatus().isBlocked()) {
    System.out.println(run.getSuspension().getMessage());
} else if (run.getStatus() == AgentRunStatus.FAILED) {
    run.getError().printStackTrace();
}
```

常见终止状态：

| 状态 | 含义 |
| --- | --- |
| `COMPLETED` | 得到最终模型消息 |
| `FAILED` | 不可恢复异常 |
| `CANCELLED` | 收到取消请求 |
| `MAX_ITERATIONS_REACHED` | 达到模型迭代上限 |
| `BUDGET_EXCEEDED` | 时间、Token 或工具调用预算耗尽 |

## 添加生命周期监听器

```java
AgentRunner runner = new AgentRunner()
    .addListener(new AgentListener() {
        @Override
        public void onToolStart(AgentRun run, ToolCall call) {
            System.out.println("执行工具：" + call.getName());
        }

        @Override
        public void onCheckpoint(AgentRun run, AgentRunSnapshot snapshot) {
            System.out.println("保存版本：" + snapshot.getVersion());
        }

        @Override
        public void onRunComplete(AgentRun run) {
            System.out.println("运行完成：" + run.getId());
        }
    });
```

监听器适合 UI 更新、日志和指标，不应该承担控制决策。需要审计和断点消费时使用持久化事件 Store。

## 下一步

- [Agent Demo 场景与运行方式](./demos/)
- [架构与核心组件](./architecture.md)
- [Agent 与 AgentRun](./agent-and-run.md)
- [工具执行与审批](./tools-and-approval.md)
- [持久化工具审批 Demo](./demos/durable-tool-agent.md)
- [平台集成与扩展](./platform-integration.md)

</div>

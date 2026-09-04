---
title: Agent 快速开始
description: 从一个空 Maven 项目开始，运行第一个会调用 Java 工具的 Agents-Flex Agent。
---

# Agent 快速开始

这篇教程会带你从零创建一个“天气助手”。用户提出天气问题后，大模型会主动调用 Java 工具，再根据工具返回的数据生成答案。

完成后，你将理解最基本的 Agent 执行过程：

```text
用户提问 -> 大模型选择工具 -> Java 工具返回结果 -> 大模型生成最终答案
```

整个示例不依赖 Spring Boot，只需要一个普通 Maven 项目。为了把注意力放在 Agent 上，天气工具会直接返回模拟数据，不会连接真实天气服务。

## 准备工作

开始前，请准备：

- JDK 8 或更高版本；
- Maven 3.6 或更高版本；
- 一个支持 Tool Calling（工具调用）的模型及其 API Key。

可以在终端中检查 Java 和 Maven 是否已经安装：

```bash
java -version
mvn -version
```

::: tip 什么是 Tool Calling？
Tool Calling 是指大模型可以根据问题选择一个工具，并生成调用这个工具所需的参数。大模型只负责选择工具，真正的 Java 方法仍由 Agents-Flex 执行。
:::

## 第 1 步：创建 Maven 项目

新建一个普通 Maven 项目，目录结构如下：

```text
agents-flex-agent-quickstart/
├── pom.xml
└── src/main/java/com/example/AgentQuickStart.java
```

将下面的内容放入 `pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>agents-flex-agent-quickstart</artifactId>
    <version>1.0-SNAPSHOT</version>

    <properties>
        <maven.compiler.source>8</maven.compiler.source>
        <maven.compiler.target>8</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <agents-flex.version>2.2.9</agents-flex.version>
    </properties>

    <dependencies>
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
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
                <version>3.1.0</version>
            </plugin>
        </plugins>
    </build>
</project>
```

这里使用了两个依赖：

| 依赖 | 作用 |
| --- | --- |
| `agents-flex-agent` | 提供 `Agent`、`AgentRunner` 和任务状态管理 |
| `agents-flex-chat-openai` | 连接 OpenAI 或兼容 OpenAI Chat Completions 协议的模型服务 |

如果项目中已经使用其他 Agents-Flex 模块，请让它们保持相同版本。最新版本可以在 [Maven Central](https://central.sonatype.com/artifact/com.agentsflex/agents-flex-agent) 查看。

## 第 2 步：配置模型信息

不要把 API Key 直接写进 Java 代码，也不要提交到 Git 仓库。建议通过环境变量传入。

macOS 或 Linux：

```bash
export AI_API_KEY="your-api-key"
export AI_MODEL="gpt-4o-mini"
```

Windows PowerShell：

```powershell
$env:AI_API_KEY="your-api-key"
$env:AI_MODEL="gpt-4o-mini"
```

如果使用其他兼容 OpenAI 协议的服务，还需要设置服务地址：

```bash
export AI_ENDPOINT="https://your-provider.example.com"
```

请将模型名称和服务地址替换为服务商提供的实际值。`AI_ENDPOINT` 填写服务根地址即可，不要重复加入 `/v1/chat/completions`。

## 第 3 步：编写完整代码

创建 `src/main/java/com/example/AgentQuickStart.java`：

```java
package com.example;

import com.agentsflex.agent.Agent;
import com.agentsflex.agent.AgentRunner;
import com.agentsflex.agent.AgentTurn;
import com.agentsflex.agent.AgentTurnStatus;
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.model.chat.openai.OpenAIChatConfig;

public class AgentQuickStart {

    public static void main(String[] args) {
        String apiKey = requireEnv("AI_API_KEY");
        String modelName = envOrDefault("AI_MODEL", "gpt-4o-mini");
        String endpoint = envOrDefault("AI_ENDPOINT", "https://api.openai.com");

        // 1. 创建支持工具调用的对话模型
        ChatModel chatModel = OpenAIChatConfig.builder()
            .apiKey(apiKey)
            .endpoint(endpoint)
            .requestPath("/v1/chat/completions")
            .model(modelName)
            .supportTool(true)
            .buildModel();

        // 2. 定义一个工具。这里返回模拟数据，不会请求真实天气接口
        Tool weatherTool = Tool.builder("get_weather", "查询指定城市的实时天气")
            .addParameter(Parameter.builder()
                .name("city")
                .type("string")
                .description("需要查询天气的城市名称，例如上海")
                .required(true)
                .build())
            .function(toolArgs -> {
                String city = String.valueOf(toolArgs.get("city"));
                System.out.println("[工具] 正在查询：" + city);
                return city + "今天有小雨，气温 22℃";
            })
            .build();

        // 3. 创建 Agent，并把模型和工具交给它
        Agent agent = Agent.builder("weather-assistant")
            .id("weather-assistant")
            .version("1")
            .description("一个简单的天气助手")
            .instructions("回答天气问题时，必须先调用 get_weather 工具。"
                + "不要猜测实时天气，并根据工具结果给出简短建议。")
            .chatModel(chatModel)
            .tool(weatherTool)
            .build();

        // 4. 执行一次用户任务
        AgentRunner runner = new AgentRunner();
        AgentTurn turn = runner.run(agent, "上海今天天气怎么样，需要带伞吗？");

        // 5. 检查状态，再读取最终答案
        if (turn.getStatus() == AgentTurnStatus.COMPLETED) {
            System.out.println("[助手] " + turn.getFinalOutput());
        } else {
            System.out.println("任务没有正常完成，当前状态：" + turn.getStatus());
        }
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("请先设置环境变量 " + name);
        }
        return value.trim();
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }
}
```

## 第 4 步：运行程序

在项目根目录执行：

```bash
mvn compile exec:java -Dexec.mainClass="com.example.AgentQuickStart"
```

如果配置正确，你会先看到工具执行日志，然后看到模型生成的答案。输出大致如下，具体措辞会因模型而异：

```text
[工具] 正在查询：上海
[助手] 上海今天有小雨，气温 22℃，建议出门带伞。
```

看到这两行信息，说明完整流程已经跑通：

1. 模型理解了用户问题。
2. 模型选择 `get_weather`，并生成了参数 `city=上海`。
3. `AgentRunner` 执行 Java 工具，并把结果发回模型。
4. 模型根据工具结果生成最终答案。

一次任务中，模型可能被调用不止一次。这个示例通常会调用两次：第一次决定使用工具，第二次根据工具结果回答用户。

## 代码中每个对象做什么

| 对象 | 在这个示例中的作用 |
| --- | --- |
| `ChatModel` | 连接大模型服务 |
| `Tool` | 描述并执行天气查询能力 |
| `Parameter` | 告诉模型天气工具需要一个 `city` 参数 |
| `Agent` | 把模型、工具和行为要求组合在一起 |
| `AgentRunner` | 推动“模型调用、工具执行、再次调用模型”的过程 |
| `AgentTurn` | 保存这一次任务的状态和最终结果 |

最重要的是区分 `Agent` 和 `AgentTurn`：`Agent` 可以被重复使用；每次调用 `runner.run(...)`，都会产生一个新的 `AgentTurn`。

## 为什么必须检查任务状态

`runner.run(...)` 会一直执行，直到任务完成、失败或进入等待状态，所以不能直接假定它一定返回答案。

本示例只处理了最常见的两种情况：

- `COMPLETED`：任务完成，可以读取 `getFinalOutput()`；
- 其他状态：任务没有正常完成，先打印状态进行排查。

后续加入人工审批、用户表单或自动重试后，任务还可能进入等待状态。具体状态说明请查看 [AgentTurn](./agent-turn)。

## 常见问题

### 提示没有设置 `AI_API_KEY`

环境变量可能只在设置它的那个终端窗口中有效。请在同一个窗口中先执行 `export` 或 PowerShell 配置命令，再运行 Maven 命令。

### 返回 401 或 403

通常表示 API Key 无效、已经过期，或者当前账号没有访问该模型的权限。请到模型服务商的控制台检查密钥和模型权限。

### 返回 404

检查 `AI_ENDPOINT` 和 `AI_MODEL` 是否正确。某些兼容服务的请求路径不是 `/v1/chat/completions`，这时需要按服务商文档修改 `.requestPath(...)`。

### 模型没有调用工具

依次检查：

- 当前模型是否支持 Tool Calling；
- 是否配置了 `.supportTool(true)`；
- 工具名称、说明和参数描述是否清楚；
- Agent 指令是否明确要求在回答实时问题前调用工具。

### 程序输出的天气不是真实天气

这是预期行为。示例中的工具固定返回模拟数据。接入真实业务时，把 `.function(...)` 中的模拟代码替换为天气 API、数据库查询或你自己的服务调用即可。

## 下一步

第一个 Agent 运行成功后，可以按需求继续学习：

- 想详细配置模型、指令和工具：阅读 [Agent 配置](./agent)。
- 想限制执行次数、Token 和运行时间：阅读[运行限制与预算](./budget)和[超时与过期](./timeouts)。
- 想在敏感操作前等待用户确认：阅读 [人工审批](./human-approval)。
- 想让任务等待用户补充参数：阅读 [表单输入](./form-input)。
- 想保存任务并在重启后恢复：阅读 [任务快照持久化](./store)。
- 想把长任务放到后台执行：阅读 [Worker](./worker)。
- 想实现连续多轮对话：阅读 [上下文管理](./context-management)。

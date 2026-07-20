# 对话模型快速开始

本教程将带你完成第一次 Agents-Flex 模型调用。结束时，你将拥有一个可以运行的 Java 程序，并掌握：

- 如何接入 OpenAI 或兼容 OpenAI Chat Completions 协议的模型服务；
- 如何进行同步调用与流式输出；
- 如何使用 `MemoryPrompt` 保存多轮对话上下文；
- 下一步如何为模型加入工具、知识与智能体能力。

整个过程不依赖 Spring Boot。已有 Spring Boot 项目的开发者也可以直接使用相同 API。

## 准备工作

开始前请确认本地已经具备：

- JDK 8 或更高版本；
- Maven 3.6 或更高版本；
- 一个可用的模型服务 API Key；
- 对应服务商支持的模型名称。

可以通过以下命令检查环境：

```bash
java -version
mvn -version
```

> Agents-Flex 的主要模块支持 JDK 8 或更高版本。若后续使用 MCP 模块，则需要 JDK 17 或更高版本。

## 创建项目

新建一个普通 Maven 项目，目录结构如下：

```text
agents-flex-quickstart/
├── pom.xml
└── src/main/java/com/example/ChatQuickStart.java
```

在 `pom.xml` 中加入 OpenAI 对话模块：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>agents-flex-quickstart</artifactId>
    <version>1.0-SNAPSHOT</version>

    <properties>
        <maven.compiler.source>8</maven.compiler.source>
        <maven.compiler.target>8</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <agents-flex.version>2.2.2</agents-flex.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>com.agentsflex</groupId>
            <artifactId>agents-flex-chat-openai</artifactId>
            <version>${agents-flex.version}</version>
        </dependency>
    </dependencies>
</project>
```

这里使用的是最小对话依赖。若希望一次引入 Agents-Flex 的模型、向量库、工具与智能体模块，也可以使用聚合依赖：

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-bom</artifactId>
    <version>2.2.2</version>
</dependency>
```

Gradle 项目可以使用：

```groovy
implementation 'com.agentsflex:agents-flex-chat-openai:2.2.2'
```

最新版本可以在 [Maven Central](https://central.sonatype.com/artifact/com.agentsflex/agents-flex-chat-openai) 查看。

## 配置 API Key

不要把 API Key 直接写进代码或提交到代码仓库。建议通过环境变量传入：

macOS / Linux：

```bash
export AI_API_KEY="your-api-key"
export AI_MODEL="gpt-4o"
```

Windows PowerShell：

```powershell
$env:AI_API_KEY="your-api-key"
$env:AI_MODEL="gpt-4o"
```

如果使用兼容 OpenAI 协议的其他服务，再设置它的 API 地址。例如：

```bash
export AI_ENDPOINT="https://your-provider.example.com"
```

`AI_ENDPOINT` 应填写服务根地址，不要重复包含 `/v1/chat/completions`。请求路径会单独配置。

## 完成第一次对话

创建 `src/main/java/com/example/ChatQuickStart.java`：

```java
package com.example;

import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.model.chat.openai.OpenAIChatConfig;

public class ChatQuickStart {

    public static void main(String[] args) {
        String apiKey = requireEnv("AI_API_KEY");
        String modelName = envOrDefault("AI_MODEL", "gpt-4o");
        String endpoint = envOrDefault("AI_ENDPOINT", "https://api.openai.com");

        ChatModel chatModel = OpenAIChatConfig.builder()
            .provider("openai-compatible")
            .endpoint(endpoint)
            .requestPath("/v1/chat/completions")
            .apiKey(apiKey)
            .model(modelName)
            .buildModel();

        String answer = chatModel.chat("请用三句话介绍 Agents-Flex");
        System.out.println(answer);
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

在 IDE 中运行 `ChatQuickStart.main()`，或者在项目目录执行：

```bash
mvn compile exec:java -Dexec.mainClass="com.example.ChatQuickStart"
```

如果程序输出了模型回复，第一次调用就完成了。

### 配置项分别做什么

| 配置 | 作用 | OpenAI 默认值 |
| --- | --- | --- |
| `provider` | 服务商标识，便于日志、观测与区分实例 | `openai` |
| `endpoint` | 模型服务根地址 | `https://api.openai.com` |
| `requestPath` | Chat Completions 请求路径 | `/v1/chat/completions` |
| `apiKey` | 服务端鉴权凭证 | 无，必须提供 |
| `model` | 服务商提供的模型名称 | `gpt-3.5-turbo` |

示例显式配置了关键字段，因此切换到其他 OpenAI 兼容服务时，通常只需改变环境变量和必要的请求路径，业务调用仍然是同一个 `ChatModel`。

## 使用流式输出

同步 `chat()` 会等待模型生成完整内容后一次返回。聊天界面通常更适合使用 `chatStream()`，在模型生成时逐段显示内容。

下面是一个完整的流式示例：

```java
package com.example;

import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.client.StreamContext;
import com.agentsflex.model.chat.openai.OpenAIChatConfig;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class StreamingChatQuickStart {

    public static void main(String[] args) throws InterruptedException {
        ChatModel chatModel = OpenAIChatConfig.builder()
            .apiKey(System.getenv("AI_API_KEY"))
            .model(System.getenv().getOrDefault("AI_MODEL", "gpt-4o"))
            .endpoint(System.getenv().getOrDefault("AI_ENDPOINT", "https://api.openai.com"))
            .requestPath("/v1/chat/completions")
            .buildModel();

        CountDownLatch completed = new CountDownLatch(1);

        chatModel.chatStream("解释 Java 中的虚拟线程", new StreamResponseListener() {
            @Override
            public void onStart(StreamContext context) {
                System.out.println("开始生成：");
            }

            @Override
            public void onMessage(StreamContext context, AiMessageResponse response) {
                String delta = response.getMessage().getContent();
                if (delta != null) {
                    System.out.print(delta);
                }
            }

            @Override
            public void onStop(StreamContext context) {
                System.out.println("\n生成完成");
                completed.countDown();
            }

            @Override
            public void onFailure(StreamContext context, Throwable throwable) {
                System.err.println("调用失败：" + throwable.getMessage());
                completed.countDown();
            }
        });

        if (!completed.await(2, TimeUnit.MINUTES)) {
            throw new IllegalStateException("等待模型响应超时");
        }
    }
}
```

`response.getMessage().getContent()` 是本次收到的增量内容，适合直接追加到控制台或前端界面。`getFullContent()` 是截至当前已经收到的完整内容，适合需要覆盖刷新整个文本区域的场景。

在 Web 应用中，不需要使用 `CountDownLatch` 阻塞请求线程。应在监听器回调中把增量转发给浏览器的 SSE、WebSocket 或其他流式通道。这里等待完成只是为了让命令行示例稳定退出。

## 进行多轮对话

每次调用 `chat(String)` 都是一次独立对话。要让模型理解“它”“刚才那个方案”等上下文，需要使用 `MemoryPrompt` 保存历史消息。

```java
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.prompt.MemoryPrompt;

MemoryPrompt prompt = new MemoryPrompt();
prompt.setSystemMessage("你是一名严谨的 Java 架构师，回答要简洁并给出理由。");

prompt.addUserMessage("为订单服务设计一个缓存方案。");
AiMessageResponse first = chatModel.chat(prompt);
System.out.println(first.getMessage().getContent());

// 把模型回复加入历史，再发起下一轮。
prompt.addMessage(first.getMessage());
prompt.addUserMessage("刚才的方案在缓存穿透时应该怎么处理？");

AiMessageResponse second = chatModel.chat(prompt);
System.out.println(second.getMessage().getContent());
prompt.addMessage(second.getMessage());
```

`MemoryPrompt` 默认使用 JVM 内存保存消息，并在请求时携带最近 100 条。实际业务通常会为每个会话创建独立的 `ChatMemory`，并根据 Token 成本、模型上下文窗口和数据保留要求实现持久化或裁剪策略。详细说明见 [Memory 记忆](./memory.md)。

> `chatModel.chat(prompt)` 不会自动把模型回复写回 `MemoryPrompt`。应用需要像示例一样明确保存 `AiMessage`，这样更容易控制哪些中间消息应该进入长期对话历史。

## 接入其他模型服务

### OpenAI 兼容服务

对于实现了 OpenAI Chat Completions 协议的服务，继续使用 `agents-flex-chat-openai` 即可：

```java
ChatModel chatModel = OpenAIChatConfig.builder()
    .provider("your-provider")
    .endpoint("https://your-provider.example.com")
    .requestPath("/v1/chat/completions")
    .apiKey(System.getenv("AI_API_KEY"))
    .model("your-model-name")
    .buildModel();
```

如果厂商的路径、请求字段或流式协议与 OpenAI 不完全一致，请使用 Agents-Flex 提供的 Qwen、DeepSeek、Ollama 等专用模块，或通过 `ChatRequestSpecBuilder`、`ChatClient` 和消息解析器扩展协议。详见 [ChatConfig](./chat-config.md)、[ChatClient](./chat-client.md) 与 [ChatRequestSpecBuilder](./chat-request-spec-builder.md)。

## 常见问题

### 提示 `apiKey must be set`

`AI_API_KEY` 未设置或当前 IDE 没有读取到终端环境变量。可以在 IDE 的 Run Configuration 中单独配置环境变量，然后重新运行。

### 返回 HTTP 401 或 403

API Key 无效、过期，或者没有访问目标模型的权限。请先在服务商控制台确认密钥和模型授权。

### 返回 HTTP 404

通常是 `endpoint` 与 `requestPath` 拼接错误。例如 endpoint 已经包含 `/v1`，同时 requestPath 又设置为 `/v1/chat/completions`。确保两者组合后正好是服务商要求的完整地址。

### 提示模型不存在

`model` 必须使用服务商 API 接受的模型标识，不一定等于控制台显示名称。确认账号所在区域、Endpoint 和模型名称属于同一服务。

### 流式调用没有任何输出

确认服务端支持 SSE 流式响应，并检查 `onFailure()` 中的异常。部分兼容服务返回的事件格式并不完全遵循 OpenAI 协议，此时需要使用对应厂商模块或自定义流式解析器。

### 日志中出现了请求内容

对话日志默认开启。处理敏感数据时，可以在 Builder 中使用 `.logEnabled(false)`，并结合 [可观测性配置](../observability/observability.md) 制定日志、追踪和脱敏策略。无论是否开启日志，都不要把 API Key 写入 Prompt、异常信息或业务日志。

## 下一步

完成模型调用后，可以沿着实际需求继续扩展：

- 让模型调用 Java 方法或业务 API：[Tool 工具调用](./tool.md)
- 构建推理与行动循环：[ReAct Agent](../agent/react-agent.md)
- 连接标准化外部工具：[MCP](./mcp.md)
- 加载可复用的脚本与能力包：[Skills](./skills.md)
- 接入企业文档和向量数据库：[RAG](../rag/document.md)
- 用自然语言安全查询数据库：[Text2SQL](./text2sql.md)
- 在多个模型节点之间负载均衡和故障切换：[模型路由](../intro/model-router.md)

你也可以直接运行仓库中的 [Hello World Demo](https://github.com/agents-flex/agents-flex/tree/main/demos/helloworld)，再从一个真实例子开始修改。

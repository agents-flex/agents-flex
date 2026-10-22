---
title: OpenAI Responses API
description: 使用 OpenAIResponsesChatModel 接入 Responses 协议，完成同步、流式与函数调用，并理解请求构建和响应解析方案。
---

# OpenAI Responses API

::: warning 开发状态
本页对应当前分支新增的 Responses 实现，尚未包含在已发布的 `2.3.0` 中。运行示例前，请使用包含这些类的源码构建版本；仅添加 Maven Central 上的 `2.3.0` 依赖无法使用本页 API。
:::

<div v-pre>

## 概述

`OpenAIResponsesChatModel` 使用 Responses 协议接入模型服务，并向业务层提供统一的 `ChatModel` 接口。业务仍通过 `Prompt`、`ChatOptions` 发起调用，使用 `AiMessageResponse` 读取正文、推理内容、ToolCall 和 Token 用量。

Responses 与 Chat Completions 使用不同的请求结构、响应对象和流式事件。接入 Responses 时，应使用 `OpenAIResponsesChatConfig` 创建模型，不能仅把 `OpenAIChatConfig` 的请求路径修改成 `/v1/responses`。

| 内容 | Chat Completions | Responses |
| --- | --- | --- |
| OpenAI 默认路径 | `/v1/chat/completions` | `/v1/responses` |
| 输入消息 | `messages` | `input` |
| 函数定义 | `tools[].function` | `tools[]` 中的 `name`、`description`、`parameters` |
| 函数调用 | `message.tool_calls` | `output` 中的 `function_call` 项 |
| 函数执行结果 | `role=tool` 的消息 | `function_call_output` 项 |
| 输出长度参数 | `max_tokens` | `max_output_tokens` |
| 流式解析 | `choices[].delta` | 按 `response.*` 事件类型解析 |

## 适用场景

- 服务提供 Responses 兼容接口，需要继续使用 Agents-Flex 的 `ChatModel`、Prompt 和拦截器。
- 需要分别读取最终答案和模型返回的推理内容。
- 需要通过 Responses 的函数调用格式执行 Java Tool，再把结果交回模型。
- 需要接入使用不同根地址或请求路径的 Responses 兼容服务。

服务商声称兼容 OpenAI 时，应进一步确认它支持的是 Chat Completions 还是 Responses，以及目标模型支持的参数和流式事件。

## 快速开始

### 添加依赖

Responses 类位于现有的 `agents-flex-chat-openai` 模块中，不需要单独的 Responses 模块：

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-chat-openai</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

`${agents-flex.version}` 应使用包含 Responses 实现的构建版本。

### 配置环境变量

示例从环境变量读取 API Key 和模型名称，模型名称以服务商提供的实际名称为准：

```bash
export OPENAI_RESPONSES_API_KEY="your-api-key"
export OPENAI_RESPONSES_MODEL="your-model-name"
```

Windows PowerShell：

```powershell
$env:OPENAI_RESPONSES_API_KEY="your-api-key"
$env:OPENAI_RESPONSES_MODEL="your-model-name"
```

环境变量由示例代码主动读取，Config 不会自动加载这些变量。

### 创建模型并发起同步调用

```java
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.prompt.SimplePrompt;
import com.agentsflex.model.chat.openai.responses.OpenAIResponsesChatConfig;
import com.agentsflex.model.chat.openai.responses.OpenAIResponsesChatModel;

OpenAIResponsesChatModel chatModel = OpenAIResponsesChatConfig.builder()
    .apiKey(System.getenv("OPENAI_RESPONSES_API_KEY"))
    .model(System.getenv("OPENAI_RESPONSES_MODEL"))
    .endpoint("https://api.openai.com")
    .requestPath("/v1/responses")
    .logEnabled(false)
    .buildModel();

AiMessageResponse response = chatModel.chat(new SimplePrompt("请用三句话介绍 Agents-Flex"));
response.throwIfError();
System.out.println(response.getMessage().getTextContent());
```

`buildModel()` 会校验 API Key。示例显式指定模型名称，运行前也应确保 `OPENAI_RESPONSES_MODEL` 已设置。

### 接入智谱兼容地址

对于 `https://open.bigmodel.cn/api/v1` 这个根地址，配置为：

```java
OpenAIResponsesChatModel chatModel = OpenAIResponsesChatConfig.builder()
    .provider("bigmodel")
    .apiKey(System.getenv("OPENAI_RESPONSES_API_KEY"))
    .model(System.getenv("OPENAI_RESPONSES_MODEL"))
    .endpoint("https://open.bigmodel.cn/api/v1")
    .requestPath("/responses")
    .logEnabled(false)
    .buildModel();
```

最终请求地址是 `https://open.bigmodel.cn/api/v1/responses`。不要再把 `requestPath` 配置成 `/v1/responses`，否则会重复拼接版本路径。

| 配置 | 作用 |
| --- | --- |
| `provider` | 服务商标识，用于区分模型实例 |
| `endpoint` | 服务根地址 |
| `requestPath` | 根地址之后的 Responses 请求路径 |
| `apiKey` | 以 Bearer Header 发送的鉴权凭证 |
| `model` | 服务商支持的模型名称 |
| `logEnabled` | 是否启用框架对话日志 |
| `retryEnabled` / `retryCount` / `retryInitialDelayMs` | 请求重试配置 |

## 读取正文、推理内容和用量

```java
import com.agentsflex.core.message.AiMessage;

AiMessageResponse response = chatModel.chat(new SimplePrompt("分析这个问题并给出结论"));
response.throwIfError();
AiMessage message = response.getMessage();

System.out.println("答案：" + message.getTextContent());
System.out.println("推理：" + message.getReasoningContent());
System.out.println("输入 Token：" + message.getPromptTokens());
System.out.println("输出 Token：" + message.getCompletionTokens());
System.out.println("总 Token：" + message.getTotalTokens());
```

同步响应中的 `message.content` 只提取 `output_text`，`reasoning.content` 中的 `reasoning_text` 单独写入 `reasoningContent`。没有推理正文时，解析器尝试读取 `reasoning.summary` 中的 `summary_text`。这可以处理兼容服务在同一响应中同时返回推理项和答案项的情况。

推理内容和 Usage 都是可选字段，是否返回取决于服务和模型。`getPromptTokensDetails()`、`getCompletionTokensDetails()` 保留服务返回的用量明细，例如缓存 Token 或推理 Token；读取前应判空。

需要分别读取字段时，使用 `chat(Prompt)` 返回完整响应。`chat(String)` 是统一接口的便捷方法，在正文为空时可能回退到推理文本。

## 流式调用

下面的示例假设 `chatModel` 已按快速开始创建：

```java
import com.agentsflex.core.message.AiMessage;
import com.agentsflex.core.model.chat.StreamResponseListener;
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.client.StreamContext;
import com.agentsflex.core.prompt.SimplePrompt;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

CountDownLatch completed = new CountDownLatch(1);
chatModel.chatStream(new SimplePrompt("解释 Java 中的虚拟线程"), new StreamResponseListener() {
    @Override
    public void onMessage(StreamContext context, AiMessageResponse response) {
        AiMessage delta = response.getMessage();
        if (delta.getContent() != null) {
            System.out.print(delta.getContent());
        }
        // 推理增量位于 delta.getReasoningContent()，可单独显示。
    }

    @Override
    public void onError(StreamContext context, Throwable error) {
        System.err.println("调用失败：" + error.getMessage());
        completed.countDown();
    }

    @Override
    public void onClose(StreamContext context) {
        AiMessage fullMessage = context.getFullMessage();
        if (fullMessage != null) {
            System.out.println("\n输出 Token：" + fullMessage.getCompletionTokens());
        }
        completed.countDown();
    }
});

if (!completed.await(2, TimeUnit.MINUTES)) {
    throw new IllegalStateException("等待模型响应超时");
}
```

`getContent()` 是本次回调的正文增量，适合追加到界面。流结束后可从 `context.getFullMessage()` 获取累计消息。`response.completed` 提供结束状态和用量，不会再次追加该事件内的完整答案。

这里使用 `CountDownLatch` 是为了让命令行示例等待结束。在 Web 应用中，应通过监听器把增量转发给 SSE 或 WebSocket 客户端。

## 函数调用

Responses 使用 `function_call` 和 `function_call_output` 关联工具请求与执行结果。框架把它们转换为统一的 `ToolCall` 和 `ToolMessage`，业务可以继续使用 [Function Call](./function-call.md) 中的执行方式。

```java
import com.agentsflex.core.model.chat.response.AiMessageResponse;
import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.prompt.MemoryPrompt;

MemoryPrompt prompt = new MemoryPrompt();
prompt.addUserMessage("杭州的天气怎么样？");
prompt.addTool(Tool.builder("get_weather", "查询指定城市的天气")
    .addParameter(Parameter.builder()
        .name("city").type("string").required(true).build())
    .function(args -> args.get("city") + "：晴，26 摄氏度")
    .build());
prompt.setToolChoice("required");

AiMessageResponse response = chatModel.chat(prompt);
response.throwIfError();
if (response.hasToolCalls()) {
    prompt.addMessage(response.getMessage());
    prompt.addMessages(response.executeToolCallsAndGetToolMessages());
    prompt.setToolChoice(null);

    AiMessageResponse answer = chatModel.chat(prompt);
    answer.throwIfError();
    System.out.println(answer.getMessage().getTextContent());
}
```

示例工具返回固定演示数据，实际业务应在函数中调用天气服务。工具结果回传前必须保存模型的调用消息，使 `call_id` 可以关联原调用。第二轮清除 `required`，让模型可以直接回答；模型也可能再次请求工具，完整业务循环可参考 [Function Call](./function-call.md)。

当前实现以单次函数调用闭环为基础，不应依赖它完整保留同一响应中的多个并行函数调用。

## 请求参数

`ChatOptions` 中已映射的参数如下：

| 框架字段 | Responses Body 字段 | 行为 |
| --- | --- | --- |
| `model` | `model` | Options 优先，Config 兜底 |
| `maxTokens` | `max_output_tokens` | 非 null 时加入 |
| `temperature` | `temperature` | 非 null 时加入 |
| `topP` | `top_p` | 非 null 时加入 |
| 流式调用 | `stream=true` | 由 `chatStream(...)` 设置 |
| `extraBody` | 自定义 Body 字段 | 直接合并到请求中 |

```java
import com.agentsflex.core.model.chat.ChatOptions;
import java.util.Collections;

ChatOptions options = new ChatOptions();
options.setMaxTokens(1024);
options.setExtraBody(Collections.<String, Object>singletonMap("store", false));

AiMessageResponse response = chatModel.chat(new SimplePrompt("介绍一下这个接口"), options);
response.throwIfError();
```

额外参数是否被接受由服务端决定。`extraBody` 在基础字段之后合并，可以覆盖 `model`、`input` 等字段；Prompt 生成的非空 `tools` 和 `tool_choice` 在之后写入。

当前 Builder 没有直接映射 `ChatOptions.stop`、`responseFormat` 等所有 Chat Completions 参数。需要特定参数时，应使用服务支持的 Responses 字段通过 `extraBody` 传入。

## 实现设计

实现沿用现有的模型调用链：

```text
Prompt + ChatOptions
        ↓
OpenAIResponsesChatModel / ChatInterceptor
        ↓
OpenAIResponsesRequestSpecBuilder
        ↓
OpenAIResponsesChatClient → HTTP / SSE
        ↓
OpenAIResponsesParser → AiMessageResponse / StreamResponseListener
```

| 类 | 所属模块 | 职责 |
| --- | --- | --- |
| `OpenAIResponsesChatConfig` | `agents-flex-chat-openai` | 配置根地址、路径、凭证和模型 |
| `OpenAIResponsesChatModel` | `agents-flex-chat-openai` | 为统一调用链选择 Responses Builder 和 Client |
| `OpenAIResponsesRequestSpecBuilder` | `agents-flex-chat-openai` | 生成传输配置、`input` 和函数定义 |
| `OpenAIResponsesChatClient` | `agents-flex-core` | 执行 HTTP/SSE、处理错误并调用解析器 |
| `OpenAIResponsesParser` | `agents-flex-core` | 解析正文、推理、函数调用及用量 |

### 请求构建

`ChatRequestSpec` 保存 URL、Header 和重试配置，Body 在拦截器链末端基于最终 Prompt 和 Options 构建。

Builder 复用 `OpenAIChatMessageSerializer` 序列化消息与工具，再转换为 Responses 结构：

- 普通消息写入 `input`，保留 `role` 与 `content`。
- Assistant 的 ToolCall 转为独立 `function_call` 项。
- ToolMessage 转为 `function_call_output`，保留 `call_id` 和执行结果。
- 函数 Schema 从嵌套的 `function` 对象转为顶层函数字段。
- 内容列表中的文本和图片转换为 `input_text`、`input_image`。

图片结构转换已包含在实现中，具体服务和模型的图片输入仍需单独验证；其他多模态及服务端内置工具不应视为已完整适配。

### 响应和事件解析

| 响应项或事件 | 框架行为 |
| --- | --- |
| `output[].type=message` 中的 `output_text` | 写入正文 |
| `output[].type=reasoning` 中的 `reasoning_text` / `summary_text` | 写入推理字段 |
| 顶层 `output_text` | 没有从 `output` 取得正文时的兼容回退 |
| `output[].type=function_call` | 转为 ToolCall，优先使用 `call_id` |
| `response.output_text.delta` | 正文增量 |
| `response.reasoning_text.delta` / `response.reasoning_summary_text.delta` | 推理增量 |
| `response.output_item.added` | 读取新增项，例如函数名称和调用 ID |
| `response.function_call_arguments.delta` | 函数参数增量 |
| `response.completed` | 标记结束并读取 Usage，避免重复正文 |

`usage.input_tokens`、`output_tokens`、`total_tokens` 分别映射到输入、输出和总 Token。两类 Token 明细对象也会保留。

同步空响应、非法 JSON 和服务端 `error` 对象会转换为错误 `AiMessageResponse`。流式传输错误通过 Listener 处理。

### 支持边界

当前实现覆盖普通文本请求、基础 SSE 事件、推理字段读取、单次函数调用和 Usage。以下能力仍需专项适配或验证：

- 同一响应中多个并行 ToolCall 的完整保留。
- `response.failed`、不完整响应、拒绝内容等其他事件或状态的专门处理。
- 完整多模态输入输出，以及服务端内置工具的结果解析。
- 服务端会话状态、后台任务、响应查询和取消。
- 多请求并发时函数项 ID 映射的隔离；当前 Parser 保存了函数项 ID 与调用 ID 的映射。

普通多轮对话可用 `MemoryPrompt` 保存并重发历史。`previous_response_id` 等字段可以通过 `extraBody` 传入，但框架不会自动维护服务端会话，也不会保存并回传所有原始推理项。

## 测试与验证

在仓库根目录运行客户端与解析器测试：

```bash
mvn -pl agents-flex-core -am \
  -Dtest=OpenAIResponsesChatClientTest,OpenAIResponsesParserTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

`OpenAIResponsesChatClientTest` 的真实请求固定使用智谱地址 `https://open.bigmodel.cn/api/v1/responses`，读取 `OPENAI_RESPONSES_API_KEY` 和 `OPENAI_RESPONSES_MODEL`。未设置 Key 时仅跳过真实请求；预设响应的解析测试仍执行。真实请求验证固定答案、消息 ID 和 Token 用量。

模型层的集成测试支持通过环境变量设置服务地址：

```bash
export OPENAI_RESPONSES_ENDPOINT="https://open.bigmodel.cn/api/v1"
export OPENAI_RESPONSES_PATH="/responses"

mvn -pl agents-flex-chat/agents-flex-chat-openai -am \
  -Dtest=OpenAIResponsesChatModelIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

这个测试类覆盖同步、流式和工具调用往返，未设置 Key 时跳过。未指定地址时默认请求 OpenAI，因此兼容服务必须同时设置 Endpoint 和 Path。测试类包含一次固定 `printf BASH_TOOL_OK` 命令的本地 Bash 工具检查，需要 `/bin/bash`。

测试成功既取决于协议处理，也取决于模型是否遵循固定答案指令；请求成功但输出额外内容或拒绝回答时，严格文本断言仍会失败。被跳过的测试不能作为真实 API 已验证的依据。

## 常见问题

### 为什么只修改请求路径后解析不出内容？

`OpenAIChatModel` 使用 Chat Completions 的 Builder 和 Parser。Responses 接口需要改用 `OpenAIResponsesChatConfig` 创建模型，同时切换请求与响应处理。

### 为什么答案中包含推理文本？

检查使用的解析器是否按内容类型区分 `reasoning_text` 和 `output_text`。本实现把两者分别保存到 `reasoningContent` 与正文；读取答案时使用 `getTextContent()`。

### 为什么服务拒绝 temperature 或其他参数？

不同模型对参数的支持不同。移除不被接受的选项，或按服务商文档配置 Responses 对应字段。

### 为什么流式调用重复显示了完整答案？

增量回调应追加 `getContent()`。如果每次追加累计正文，会造成重复；本解析器在完成事件中只读取状态和用量，不再次追加完整输出。

## 下一步

- [ChatModel](./chat-model.md)
- [ChatRequestSpecBuilder](./chat-request-spec-builder.md)
- [ChatClient](./chat-client.md)
- [AiMessageParser](./ai-message-parser.md)
- [Function Call](./function-call.md)

</div>

Responses 协议可参考 [OpenAI 官方 API 文档](https://platform.openai.com/docs/api-reference/responses)；推理摘要可参考 [OpenAI 官方推理指南](https://developers.openai.com/api/docs/guides/reasoning)。兼容服务的实际支持范围以其文档和验证结果为准。

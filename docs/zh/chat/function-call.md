---
title: Function Call
description: 理解大模型 Function Call 的协议原理，并使用 ChatModel、ToolCall、Tool 和 ToolMessage 完成可靠的工具调用闭环。
---

# Function Call

## 概述

Function Call（也常称 Function Calling 或 Tool Calling）是一种让大模型输出**结构化调用意图**的机制。应用把可用函数的名称、说明和参数 Schema 发给模型，模型根据用户问题决定是否调用，并返回函数名与参数；真正的 Java 方法、数据库或外部 API 仍由应用执行。

```text
应用提供函数定义
       ↓
用户问题 + Messages + Tool Schema
       ↓
ChatModel 调用模型
       ↓
模型返回 ToolCall(name, arguments, id)
       ↓
应用校验并执行 Tool
       ↓
ToolMessage(toolCallId, result) 回传模型
       ↓
模型结合结果生成最终回答
```

::: warning 重要边界
Function Call 不是远程代码执行。模型不会调用 JVM 中的方法，也不能直接访问数据库；它只生成一份“建议调用哪个函数、传入什么参数”的结构化数据。应用始终掌握执行、拒绝、修改和审计的决定权。
:::

## 适用场景

Function Call 适合把语言理解连接到确定性的业务能力：

- 查询实时天气、订单、库存、物流和账户状态。
- 调用计算器、搜索引擎、Text2SQL、MCP 或内部 HTTP API。
- 把自然语言转换成结构化筛选条件或业务参数。
- 创建工单、发送通知、执行审批后的业务操作。
- 让模型在多个能力之间选择下一步操作。

以下场景通常不需要 Function Call：

- 只做摘要、改写、翻译或普通问答，直接调用 `chat(...)` 即可。
- 只需要补充静态知识，优先使用 Prompt 或 RAG。
- 操作不能提供可靠的权限校验、幂等和审计时，不应直接暴露为可执行 Tool。

## 快速开始

下面通过“查询天气”完成最小的 Function Call 闭环。假设 `chatModel` 已按照 [ChatModel 快速开始](./chat-model.md#快速开始) 创建。

### 1. 定义可调用函数

使用 `@ToolDef` 描述函数，使用 `@ToolParam` 描述参数：

```java
public class WeatherTools {

    @ToolDef(
        name = "get_weather",
        description = "查询指定城市的实时天气"
    )
    public String getWeather(
        @ToolParam(
            name = "city",
            description = "城市名称，例如北京或上海",
            required = true
        ) String city
    ) {
        return city + "：晴，26 摄氏度";
    }
}
```

描述和参数不是普通注释，它们会成为模型请求中的 Tool Schema。模型依靠这些信息选择函数并填写参数。

### 2. 把函数提供给 ChatModel

```java
MemoryPrompt prompt = new MemoryPrompt();
prompt.addUserMessage("北京今天需要带伞吗？");
prompt.addToolsFromObject(new WeatherTools());

AiMessageResponse response = chatModel.chat(prompt);
```

`addToolsFromObject(...)` 会扫描对象中带 `@ToolDef` 的方法，将其转换为 Agents-Flex 的 `Tool`。Tool 属于 `Prompt`，不是某一条 `UserMessage` 的内容。

### 3. 执行并回传结果

```java
if (response.hasToolCalls()) {
    // 保存模型提出的调用，不能只保存工具结果。
    prompt.addMessage(response.getMessage());

    List<ToolMessage> toolMessages =
        response.executeToolCallsAndGetToolMessages();
    prompt.addMessages(toolMessages);

    AiMessageResponse finalResponse = chatModel.chat(prompt);
    System.out.println(finalResponse.getMessage().getContent());
} else {
    System.out.println(response.getMessage().getContent());
}
```

第一次响应通常只有 ToolCall，不一定包含可直接展示的答案。工具执行结果回传后，第二次模型调用才会生成“北京今天晴天，通常不需要带伞”这样的自然语言回答。

## Function Call 的原理

### 先理解“模型协议”

这里的“模型协议”不是 HTTP、TCP 等网络传输协议，而是模型服务对 **Chat API 请求与响应 JSON 结构**的约定。它规定应用应该把消息和函数定义放在哪些字段中，以及模型应该用什么字段返回调用意图。

以 OpenAI 兼容协议为例，一次 Function Call 涉及四类数据：

| 数据 | 方向 | 协议字段 | 作用 |
| --- | --- | --- | --- |
| 对话消息 | 应用 -> 模型 | `messages` | 告诉模型用户问题和已有上下文 |
| 函数定义 | 应用 -> 模型 | `tools` | 告诉模型有哪些函数、用途和参数结构 |
| 函数调用意图 | 模型 -> 应用 | `tool_calls` | 告诉应用模型选择了哪个函数以及建议参数 |
| 函数执行结果 | 应用 -> 模型 | `messages` 中 `role=tool` 的消息 | 把真实执行结果关联回原调用 |

`tools` 和 `tool_calls` 容易混淆：

- `tools` 是应用在请求前提供的**能力说明书**，可能包含多个候选函数。
- `tool_calls` 是模型在响应中产生的**调用请求**，只包含本轮选中的函数。
- 两者都不包含 Java 函数体，模型服务看不到也不会执行应用代码。

#### 第一次请求：发送消息和函数定义

应用第一次请求模型时，会同时发送用户消息和可用函数：

```json
{
  "model": "your-model",
  "messages": [
    {
      "role": "user",
      "content": "北京今天需要带伞吗？"
    }
  ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "get_weather",
        "description": "查询指定城市的实时天气",
        "parameters": {
          "type": "object",
          "properties": {
            "city": {
              "type": "string",
              "description": "城市名称，例如北京或上海"
            }
          },
          "required": ["city"]
        }
      }
    }
  ],
  "tool_choice": "auto"
}
```

`function.parameters` 使用 JSON Schema 描述参数。它只声明参数名称、类型、说明、必填项和枚举等约束，不携带具体值。上例表达的是“存在一个名为 `get_weather` 的函数，它需要一个必填的字符串参数 `city`”。

`tool_choice` 控制模型能否或是否必须选择函数。它不是 Function Call 的必要字段；不发送时通常使用模型服务的默认策略。

#### 第一次响应：模型返回调用意图

模型认为必须先取得实时天气，因此不会编造结果，而是在 Assistant 消息中返回 `tool_calls`：

```json
{
  "choices": [
    {
      "message": {
        "role": "assistant",
        "content": null,
        "tool_calls": [
          {
            "id": "call_abc123",
            "type": "function",
            "function": {
              "name": "get_weather",
              "arguments": "{\"city\":\"北京\"}"
            }
          }
        ]
      }
    }
  ]
}
```

这里的 `arguments` 是模型生成的参数值，通常是一个 JSON 字符串。它与请求中的 `parameters` 不同：

- `parameters` 是应用提供的参数 Schema，例如 `city` 必须是字符串。
- `arguments` 是模型依据用户问题填写的值，例如 `{"city":"北京"}`。

模型响应到这里就结束了。模型服务不会等待 Java 方法，也不会主动执行下一轮请求；应用需要解析 `tool_calls`、找到同名 Tool 并执行。

#### 第二次请求：回传原调用和执行结果

应用执行 `get_weather` 后，需要把原 Assistant 消息和 ToolMessage 一起放回 `messages`：

```json
{
  "model": "your-model",
  "messages": [
    {
      "role": "user",
      "content": "北京今天需要带伞吗？"
    },
    {
      "role": "assistant",
      "content": null,
      "tool_calls": [
        {
          "id": "call_abc123",
          "type": "function",
          "function": {
            "name": "get_weather",
            "arguments": "{\"city\":\"北京\"}"
          }
        }
      ]
    },
    {
      "role": "tool",
      "tool_call_id": "call_abc123",
      "content": "北京：晴，26 摄氏度"
    }
  ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "get_weather",
        "description": "查询指定城市的实时天气",
        "parameters": {
          "type": "object",
          "properties": {
            "city": {"type": "string"}
          },
          "required": ["city"]
        }
      }
    }
  ]
}
```

`tool_call_id` 必须与第一次响应中的 `tool_calls[].id` 一致。模型据此把“北京：晴，26 摄氏度”识别为本次天气查询的结果，然后生成最终自然语言回答，或者继续返回下一批 `tool_calls`。

如果模型认为信息已经足够，第二次响应会返回普通 Assistant 消息，不再包含 `tool_calls`：

```json
{
  "choices": [
    {
      "message": {
        "role": "assistant",
        "content": "北京今天是晴天，气温约 26 摄氏度，通常不需要带伞。"
      },
      "finish_reason": "stop"
    }
  ]
}
```

应用可以把 `message.content` 作为最终回答展示给用户。若第二次响应仍包含 `tool_calls`，说明模型还需要其他外部信息，应用应继续执行“保存 AiMessage -> 执行 Tool -> 添加 ToolMessage -> 再次调用模型”的循环，并设置最大轮数防止无限调用。

不同厂商可能使用不同字段名称、嵌套结构或 Tool Message 规则，但核心语义一致：**先声明能力，模型生成调用意图，应用执行，再把结果关联回原调用**。Agents-Flex 的模型适配层负责处理这些协议差异，上层统一使用 `Tool`、`ToolCall` 和 `ToolMessage`。

### 第一步：把 Tool 转成模型协议

理解上述 JSON 约定后，再看 Agents-Flex 的转换过程。应用首先用 Java 定义一个 Tool：

```java
public class WeatherTools {

    @ToolDef(
        name = "get_weather",
        description = "查询指定城市的实时天气"
    )
    public String getWeather(
        @ToolParam(
            name = "city",
            description = "城市名称，例如北京或上海",
            required = true
        ) String city
    ) {
        return weatherService.getWeather(city);
    }
}
```

然后把这个 Java 对象注册到当前 Prompt：

```java
MemoryPrompt prompt = new MemoryPrompt();
prompt.addUserMessage("北京今天需要带伞吗？");
prompt.addToolsFromObject(new WeatherTools());

AiMessageResponse response = chatModel.chat(prompt);
```

`prompt.addToolsFromObject(...)` 内部使用 `ToolScanner` 扫描 `@ToolDef` 方法，并创建 `JavaMethodTool`。框架从 Java 方法和注解中得到协议所需信息：

| Java 定义 | Tool 属性 | 模型协议字段 |
| --- | --- | --- |
| `@ToolDef.name` | `Tool.name` | `function.name` |
| `@ToolDef.description` | `Tool.description` | `function.description` |
| `@ToolParam.name` | `Parameter.name` | `parameters.properties` 中的字段名 |
| Java 参数类型 `String` | `Parameter.type=string` | `properties.city.type` |
| `@ToolParam.description` | `Parameter.description` | `properties.city.description` |
| `@ToolParam.required=true` | `Parameter.required` | `parameters.required` |

`ChatMessageSerializer` 随后读取这些 `Tool` 属性，将它们序列化为对应模型协议的函数定义。对于 OpenAI 兼容模型，以上 Java 代码会生成第一次请求中 `tools` 数组的一个元素：

```json
{
  "type": "function",
  "function": {
    "name": "get_weather",
    "description": "查询指定城市的实时天气",
    "parameters": {
      "type": "object",
      "properties": {
        "city": {
          "type": "string",
          "description": "城市名称，例如北京或上海"
        }
      },
      "required": ["city"]
    }
  }
}
```

不同模型厂商的字段可能不同。`ChatMessageSerializer` 负责把统一的 Agents-Flex Tool 转换成厂商协议；模型配置的 `supportTool` 为 `false` 时不会发送 Tool 定义。

### 第二步：模型生成 ToolCall

模型判断需要查询天气后，可能返回：

```json
{
  "id": "call_abc123",
  "type": "function",
  "function": {
    "name": "get_weather",
    "arguments": "{\"city\":\"北京\"}"
  }
}
```

Agents-Flex 将其解析为 `ToolCall`：

| 字段 | 含义 |
| --- | --- |
| `id` | 本次调用的关联 ID，后续 ToolMessage 必须使用它 |
| `name` | 模型选择的函数名称，用于匹配 Prompt 中注册的 Tool |
| `arguments` | 模型生成的 JSON 参数字符串 |

在 Java 中，可以从 `AiMessageResponse` 读取这些字段：

```java
AiMessageResponse response = chatModel.chat(prompt);
response.throwIfError();

if (!response.hasToolCalls()) {
    System.out.println(response.getMessage().getContent());
    return;
}

AiMessage aiMessage = response.getMessage();
for (ToolCall call : aiMessage.getToolCalls()) {
    System.out.println("id = " + call.getId());
    System.out.println("name = " + call.getName());
    System.out.println("arguments = " + call.getArguments());

    Map<String, Object> args = call.getArgsMap();
    String city = (String) args.get("city");
    System.out.println("city = " + city);
}
```

对于前面的协议响应，这段代码读取到的结果是：

```text
id = call_abc123
name = get_weather
arguments = {"city":"北京"}
city = 北京
```

模型协议中的字段与 Java 对象对应如下：

| 响应 JSON 路径 | Agents-Flex Java API |
| --- | --- |
| `choices[0].message.tool_calls` | `AiMessage.getToolCalls()` |
| `tool_calls[].id` | `ToolCall.getId()` |
| `tool_calls[].function.name` | `ToolCall.getName()` |
| `tool_calls[].function.arguments` | `ToolCall.getArguments()` |
| arguments JSON 对象 | `ToolCall.getArgsMap()` |

具体模型的 `AiMessageParser` 负责读取厂商响应，并将不同的嵌套结构归一化成同一个 `ToolCall`。因此，上层业务不需要直接解析 `choices[0].message.tool_calls`。

`ToolCall.getArgsMap()` 会先按标准 JSON 解析；模型产生单引号、未加引号的值或夹带说明文字时，还会尝试通过 `JsonSanitizer` 容错。容错解析不等于业务校验，应用仍需检查类型、范围和权限。

### 第三步：应用匹配并执行 Tool

第二步得到的 `ToolCall` 仍只是一份协议数据。应用需要使用其中的 `function.name` 查找第一步注册的 Java Tool，再把 `function.arguments` 转换为 Java 参数。

协议字段与本地执行的关系是：

| ToolCall 协议数据 | 本地用途 |
| --- | --- |
| `id=call_abc123` | 暂时保留，执行完成后用于关联 ToolMessage |
| `name=get_weather` | 作为 key 从 `Prompt.getToolsMap()` 查找 Tool |
| `arguments={"city":"北京"}` | 解析为 Map，再传给 `Tool.invoke(args)` |

`AiMessageResponse.getToolExecutors()` 会执行以下匹配：

```text
ToolCall.name
      ↓
Prompt.getToolsMap()
      ↓
同名 Tool
      ↓
ToolExecutor
      ↓
ToolInterceptor 链 -> Tool.invoke(args)
```

对应的 Java 代码如下：

```java
ToolCall call = response.getMessage().getToolCalls().get(0);

// function.name -> Prompt 中同名的 Java Tool
Map<String, Tool> tools = prompt.getToolsMap();
Tool tool = tools.get(call.getName());
if (tool == null) {
    throw new IllegalStateException(
        "No registered Tool named: " + call.getName()
    );
}

// ToolExecutor 内部解析 arguments，并经过 ToolInterceptor 链执行 Tool。
ToolExecutor executor = new ToolExecutor(tool, call);
Object result = executor.execute();

System.out.println(result); // 北京：晴，26 摄氏度
```

也可以让响应对象完成名称匹配：

```java
List<ToolExecutor> executors = response.getToolExecutors();
for (ToolExecutor executor : executors) {
    Object result = executor.execute();
}
```

这一阶段完全发生在应用进程内。`ToolExecutor.execute()` 不会向模型服务发送 HTTP 请求，也不会自动开始第二轮对话。执行结果此时只是一个普通 Java 对象，必须在第四步转换为模型协议能够识别的 Tool Message。

如果模型返回的名称没有对应 Tool，该调用不会生成执行器。工具名必须稳定且在同一个 Prompt 中唯一；重名 Tool 在名称 Map 中会覆盖之前的实现。

### 第四步：用 ToolMessage 关联结果

工具结果不能作为普通 UserMessage 回传。应用需要先保留包含 ToolCall 的原 AiMessage，再创建带有相同调用 ID 的 `ToolMessage`：

```java
// 保留模型原始 ToolCall，维持协议上下文。
prompt.addMessage(response.getMessage());

ToolCall call = response.getMessage().getToolCalls().get(0);

ToolMessage toolMessage = new ToolMessage();
toolMessage.setToolCallId(call.getId());
toolMessage.setContent("北京：晴，26 摄氏度");
prompt.addMessage(toolMessage);

AiMessageResponse finalResponse = chatModel.chat(prompt);
System.out.println(finalResponse.getMessage().getContent());
```

普通场景可以直接执行 Tool 并生成对应消息：

```java
prompt.addMessage(response.getMessage());

List<ToolMessage> toolMessages =
    response.executeToolCallsAndGetToolMessages();
prompt.addMessages(toolMessages);

AiMessageResponse finalResponse = chatModel.chat(prompt);
```

`executeToolCallsAndGetToolMessages()` 会依次执行匹配的 Tool。返回值是字符串或数字时直接转成文本，其他 Java 对象通过 JSON 序列化写入 `ToolMessage.content`。

`ChatMessageSerializer` 会把 Java `ToolMessage` 序列化为模型协议中的 `role=tool` 消息：

```json
{
  "role": "tool",
  "tool_call_id": "call_abc123",
  "content": "北京：晴，26 摄氏度"
}
```

第二次请求中，原 Assistant ToolCall 和执行结果必须同时存在：

```json
{
  "messages": [
    {
      "role": "user",
      "content": "北京今天需要带伞吗？"
    },
    {
      "role": "assistant",
      "content": null,
      "tool_calls": [
        {
          "id": "call_abc123",
          "type": "function",
          "function": {
            "name": "get_weather",
            "arguments": "{\"city\":\"北京\"}"
          }
        }
      ]
    },
    {
      "role": "tool",
      "tool_call_id": "call_abc123",
      "content": "北京：晴，26 摄氏度"
    }
  ]
}
```

模型通过 `tool_call_id` 知道这是哪一次调用的结果。Java 对象与第二次请求字段的对应关系是：

| Java 对象 | 模型协议字段 |
| --- | --- |
| `AiMessage.toolCalls` | Assistant 消息的 `tool_calls` |
| `ToolCall.id` | `tool_calls[].id` |
| `ToolMessage.toolCallId` | Tool 消息的 `tool_call_id` |
| `ToolMessage.content` | Tool 消息的 `content` |

完整消息顺序必须保持：

```text
UserMessage
AiMessage（包含 ToolCall）
ToolMessage（包含相同 toolCallId）
AiMessage（最终回答或下一批 ToolCall）
```

不能只保存 ToolMessage，也不能把包含 ToolCall 的 AiMessage 从上下文中截断。

## Function Call 与 Tool 的关系

这两个概念经常被混用，但它们处于不同层次：

| 概念 | 所在一侧 | 职责 |
| --- | --- | --- |
| Function Call / ToolCall | 模型协议侧 | 表达模型希望调用什么以及建议参数 |
| `Tool` | 应用执行侧 | 定义名称、描述、参数 Schema 和 Java 执行逻辑 |
| `ToolExecutor` | 框架执行层 | 把 ToolCall 与 Tool 绑定，并经过拦截器链执行 |
| `ToolMessage` | 协议回传侧 | 将执行结果关联到原 ToolCall |

因此，一份 Tool 定义同时承担两项职责：

1. `name`、`description`、`parameters` 被发送给模型，用于产生 Function Call。
2. `invoke(args)` 留在应用内部，在模型返回 ToolCall 后执行。

## 先观察 Function Call，再决定是否执行

高风险业务不应收到 ToolCall 后立即执行。可以先读取结构化调用：

```java
AiMessageResponse response = chatModel.chat(prompt);

if (response.hasToolCalls()) {
    for (ToolCall call : response.getMessage().getToolCalls()) {
        System.out.println("callId = " + call.getId());
        System.out.println("name = " + call.getName());
        System.out.println("arguments = " + call.getArguments());

        Map<String, Object> args = call.getArgsMap();
        validatePermission(call.getName(), args);
        validateBusinessRules(call.getName(), args);
    }
}
```

这一层适合做：

- 校验当前用户是否有权调用该工具。
- 检查租户、订单归属、金额范围等业务约束。
- 对付款、删除、发布等操作请求人工确认。
- 为 ToolCall 生成审计记录和幂等键。
- 拒绝模型生成但当前请求未注册的函数名称。

模型参数始终是不可信输入，即使它满足 JSON Schema，也不代表它满足业务规则。

## 手动执行 Function Call

辅助方法适合普通场景；需要完全控制执行过程时，可以手动使用 `ToolExecutor`：

```java
prompt.addMessage(response.getMessage());

for (ToolExecutor executor : response.getToolExecutors()) {
    ToolCall call = executor.getToolCall();

    validatePermission(call.getName(), call.getArgsMap());
    Object result = executor.execute();

    ToolMessage toolMessage = new ToolMessage();
    toolMessage.setToolCallId(
        StringUtil.hasText(call.getId()) ? call.getId() : call.getName()
    );
    toolMessage.setContent(
        result instanceof CharSequence || result instanceof Number
            ? result.toString()
            : JSON.toJSONString(result)
    );
    prompt.addMessage(toolMessage);
}

AiMessageResponse finalResponse = chatModel.chat(prompt);
```

手动方式可以在执行前审批，在执行后脱敏结果，或者为不同 Tool 使用不同超时和错误策略。仍建议通过 `ToolExecutor.execute()` 调用，直接执行 `tool.invoke(...)` 会绕过 ToolInterceptor、观测和上下文。

## 构建 Tool 的三种方式

### 注解扫描：适合稳定的 Java 方法

```java
public class OrderTools {

    @ToolDef(name = "get_order", description = "根据订单号查询订单状态，只读操作")
    public OrderView getOrder(
        @ToolParam(name = "orderNo", description = "订单号", required = true)
        String orderNo
    ) {
        return orderService.find(orderNo);
    }
}

prompt.addToolsFromObject(new OrderTools());
```

### Map Builder：适合动态函数

```java
Tool calculator = Tool.builder("add", "计算两个整数之和")
    .addParameter(Parameter.builder()
        .name("a").type("integer").description("第一个整数")
        .required(true).build())
    .addParameter(Parameter.builder()
        .name("b").type("integer").description("第二个整数")
        .required(true).build())
    .function(args ->
        ((Number) args.get("a")).intValue()
            + ((Number) args.get("b")).intValue())
    .build();

prompt.addTool(calculator);
```

### 类型化 Builder：适合复杂输入

```java
Tool createTicket = Tool.builder(
        "create_ticket",
        CreateTicketInput.class,
        input -> ticketService.create(input)
    )
    .description("创建客服工单")
    .build();

prompt.addTool(createTicket);
```

更完整的参数、对象、数组和枚举定义见 [Tool 构建](./tool-build.md)、[ToolScanner](./tool-scanner.md) 与 [Tool.Builder](./tool-builder.md)。

## 处理连续多轮 Function Call

模型拿到第一个工具结果后，可能继续请求另一个工具。例如先查询订单，再查询物流。因此生产代码应使用有上限的循环，而不是只处理一次：

```java
AiMessageResponse response = chatModel.chat(prompt);
int maxRounds = 8;

for (int round = 0; response.hasToolCalls(); round++) {
    if (round >= maxRounds) {
        throw new IllegalStateException("Function Call rounds exceeded: " + maxRounds);
    }

    prompt.addMessage(response.getMessage());

    List<ToolMessage> results =
        response.executeToolCallsAndGetToolMessages();
    if (results.isEmpty()) {
        throw new IllegalStateException("No registered Tool matches model ToolCall");
    }

    prompt.addMessages(results);
    response = chatModel.chat(prompt);
}

response.throwIfError();
System.out.println(response.getMessage().getContent());
```

循环上限可以防止错误的 Tool 描述、模型重复尝试或工具错误造成无限调用。对调用次数、总耗时和 Token 还需要设置业务级预算。

## 一次返回多个 ToolCall

模型可能在一次 AiMessage 中返回多个调用。`executeToolCallsAndGetToolMessages()` 默认按响应顺序同步执行，并为每个调用生成一个 ToolMessage。

不要默认并行执行所有调用：

- 两个只读查询且互不依赖时，可以在自行评估后并行。
- 后一个工具依赖前一个结果时必须顺序执行。
- 创建、扣款、发送等有副作用工具应顺序执行，并使用稳定幂等键。
- 一组 ToolCall 中任何一个失败时，应明确决定终止、回传错误还是继续其他调用。

## 控制模型是否调用函数

默认情况下通常由模型自行决定直接回答还是调用 Tool。`Prompt.setToolChoice(...)` 可以传递统一的字符串选择策略：

```java
prompt.setToolChoice("required");
```

常见模型协议可能支持：

- `auto`：模型自行决定。
- `none`：禁止调用函数。
- `required`：要求至少调用一个函数。

具体取值和语义由模型服务商决定，并非所有模型都支持。如果需要强制某个指定函数，OpenAI 兼容协议通常要求对象形式的 `tool_choice`；这属于厂商参数，可以通过 `ChatOptions.extraBody` 覆盖，但应先核对对应服务的协议。

Tool 数量较多时，不要一次发送全部 Schema。可以使用 [ToolGroup](./tool-group.md) 按请求装配工具，或使用 [ToolSearch](./tool-search.md) 让模型渐进发现能力。

## 流式 Function Call

流式响应中的函数名和 arguments 可能拆分在多个 delta 中。不要在普通增量到达时执行 Tool，应等待最终聚合消息：

```java
chatModel.chatStream(prompt, new StreamResponseListener() {
    @Override
    public void onMessage(StreamContext context, AiMessageResponse response) {
        AiMessage message = response.getMessage();
        if (!message.isFinalDelta()) {
            return;
        }

        if (response.hasToolCalls()) {
            prompt.addMessage(message);
            prompt.addMessages(
                response.executeToolCallsAndGetToolMessages()
            );

            // 下一轮可以继续同步调用，也可以重新发起一次流式调用。
            AiMessageResponse answer = chatModel.chat(prompt);
            System.out.println(answer.getMessage().getContent());
        }
    }
});
```

如果具体模型的流式 ToolCall 不完整，应检查该模型 Parser 是否正确聚合 `id`、`name` 和 `arguments`。详细解析机制见 [AiMessageParser](./ai-message-parser.md)。

## 错误应该如何回传

工具执行可能出现参数错误、权限拒绝、超时和业务失败。处理方式取决于工具性质：

1. **可修正的参数错误**：返回稳定的错误码和字段说明，让模型修正参数后重试。
2. **权限或审批拒绝**：不要执行函数，回传明确但不泄露策略细节的结果。
3. **临时依赖失败**：应用控制有限重试；不要让模型无限重复调用。
4. **有副作用操作结果未知**：先用幂等键查询原操作状态，不能直接重做。
5. **未知 Tool 名称**：停止自动执行并记录协议错误，不应按名称反射任意方法。

工具错误结果仍需使用与原调用匹配的 ToolMessage。例如：

```java
ToolMessage rejected = new ToolMessage();
rejected.setToolCallId(call.getId());
rejected.setContent(
    "{\"success\":false,\"code\":\"PERMISSION_DENIED\",\"message\":\"当前用户无权执行该操作\"}"
);
prompt.addMessage(rejected);
```

不要把 Java 堆栈、数据库连接、密钥或内部网络地址返回给模型。

## 安全与生产建议

- 只把当前用户有权使用的 Tool 加入 Prompt，而不是注册后再依赖模型自觉不调用。
- Tool 的租户 ID、用户 ID 等可信上下文应由服务端或拦截器注入，不要让模型填写。
- 对金额、资源归属、枚举范围和字符串长度做服务端校验。
- 对创建、修改、删除、支付、发送等操作使用 ToolCall ID 或业务键保证幂等。
- 使用 [ToolInterceptor](./tool-interceptor.md) 统一完成鉴权、审计、限流、缓存和观测。
- 日志可以记录 Tool 名称、调用 ID、耗时和结果状态，但参数与结果需要脱敏。
- 不要把异常信息直接交给模型；先映射成稳定的业务错误结构。
- 工具描述应写清“何时使用”和“不能做什么”，降低选错工具的概率。

## ChatModel Function Call 与 Agent 的选择

| 需求 | 推荐方式 |
| --- | --- |
| 一到两次工具调用，流程由业务代码掌控 | `ChatModel` + 手动 Function Call 循环 |
| 需要在执行前精确审批或改写参数 | 手动检查 `ToolCall` + `ToolExecutor` |
| 多步骤任务、持续调用工具、预算和状态管理 | Agent |
| 需要挂起恢复、人工审批、Snapshot 和持久化 | AgentRunner |

`ChatModel` 提供协议与调用基础，但不会自动保存历史、控制循环或恢复任务。Agent 在这套 ToolCall/ToolMessage 协议之上增加了状态机、预算、审批和持久化能力。可从 [Agent 快速开始](../agent/getting-started.md) 继续阅读。

## 常见问题

### 为什么模型没有返回 ToolCall？

确认 Tool 已加入本次 Prompt，模型配置支持 Tool，并且模型本身具备 Function Call 能力。名称、描述和参数过于模糊时，模型也可能选择直接回答。

### 为什么有 ToolCall，但没有执行器？

`ToolCall.name` 必须与当前 Prompt 中某个 Tool 的名称完全一致。Tool 未注册、被 ToolGroup 过滤或名称变化时，`getToolExecutors()` 会跳过该调用。

### 为什么工具结果回传后模型无法理解？

检查消息顺序是否包含原始 AiMessage，并确认 `ToolMessage.toolCallId` 与原 `ToolCall.id` 一致。不能把工具结果作为 UserMessage 回传。

### Function Call 可以只用于提取结构化参数吗？

可以。读取 `ToolCall.getArgsMap()` 并由业务自行处理即可，不一定调用 `Tool.invoke(...)`。但如果目标只是固定 JSON 输出，也可以比较模型的结构化输出能力，避免引入不必要的工具循环。

### Tool 执行结果会自动写入 Memory 吗？

不会。`executeToolCallsAndGetToolMessages()` 只返回消息列表，应用必须显式调用 `prompt.addMessage(...)` 和 `prompt.addMessages(...)`。

## 下一步

- [Tool 构建](./tool-build.md)：深入注解、Builder 和复杂参数 Schema。
- [Tool 拦截器](./tool-interceptor.md)：加入鉴权、审计、缓存和观测。
- [ToolGroup](./tool-group.md) 与 [ToolSearch](./tool-search.md)：管理大量工具。
- [MCP](./mcp.md)：把 MCP Server 能力接入同一 Function Call 流程。

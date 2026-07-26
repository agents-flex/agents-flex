---
title: Tool 工具调用
description: 让大模型通过结构化参数调用 Java 方法、业务 API 和外部系统，并把执行结果带回对话。
---

# Tool 工具调用

## 概述

大模型擅长理解意图和组织语言，但它本身不知道实时天气、订单状态，也不能直接操作数据库。`Tool` 把这些外部能力描述成模型可理解的函数：模型负责决定调用哪个工具并生成参数，应用负责校验和执行，随后把结果作为 `ToolMessage` 发回模型。

一次工具调用不是一次普通问答，而是一个闭环：

```text
用户问题 -> 模型返回 ToolCall -> 应用执行 Tool
        -> ToolMessage 回传 -> 模型生成最终回答
```

::: warning 责任边界
模型只提出调用请求。Agents-Flex 不会在第一次 `chat(...)` 后自动完成第二轮对话；应用需要显式执行工具、保存 `AiMessage` 和 `ToolMessage`，再调用模型。
:::

## 适用场景

- 查询订单、库存、账户余额等实时业务数据。
- 调用搜索、邮件、工单、支付或内部 HTTP API。
- 执行可验证的计算、格式转换和规则判断。
- 让 Agent 创建任务或修改业务状态。

纯文本知识且更新不频繁时，优先放在提示词或知识库中；只有确实需要外部执行或实时数据时才使用 Tool。

## 快速开始

下面用一个天气方法完成完整调用链。

```java
public class WeatherTools {
    @ToolDef(name = "get_weather", description = "查询指定城市的实时天气")
    public String getWeather(
        @ToolParam(name = "city", description = "城市名称", required = true)
        String city
    ) {
        return city + "：晴，26 摄氏度";
    }
}
```

把工具添加到 `Prompt`，而不是 `UserMessage`：

```java
MemoryPrompt prompt = new MemoryPrompt();
prompt.addUserMessage("北京今天需要带伞吗？");
prompt.addToolsFromObject(new WeatherTools());

AiMessageResponse first = chatModel.chat(prompt);
if (first.hasToolCalls()) {
    // ToolCall 也必须进入历史，模型才能关联后续结果。
    prompt.addMessage(first.getMessage());

    List<ToolMessage> results =
        first.executeToolCallsAndGetToolMessages();
    prompt.addMessages(results);

    AiMessageResponse answer = chatModel.chat(prompt);
    System.out.println(answer.getMessage().getContent());
}
```

`executeToolCallsAndGetToolMessages()` 会按名称从本次响应的 `ChatContext.getPrompt()` 中找到 Tool，通过 `ToolExecutor` 执行，并把字符串、数字或 JSON 结果转换成 `ToolMessage`。

## 核心对象

| 对象 | 职责 |
| --- | --- |
| `Tool` | 定义名称、描述、参数和 `invoke(...)` 执行逻辑 |
| `Parameter` | 描述模型要生成的参数 Schema |
| `ToolCall` | 模型返回的工具名、调用 ID 和参数 |
| `ToolExecutor` | 解析参数并经过拦截器链调用 Tool |
| `ToolMessage` | 将工具结果及对应 `toolCallId` 回传给模型 |

工具名在同一个 Prompt 中应唯一。`Prompt.getToolsMap()` 按名称构建 Map，重名工具会覆盖前一个工具的执行映射。

## 两种定义方式

稳定的业务方法适合使用注解：

```java
List<Tool> tools = ToolScanner.scan(new WeatherTools());
prompt.addTools(tools);
```

运行时生成的能力适合使用 Builder：

```java
Tool stockTool = Tool.builder("get_stock")
    .description("查询商品的可售库存")
    .addParameter(Parameter.builder()
        .name("sku").type("string").description("商品 SKU")
        .required(true).build())
    .function(args -> inventoryService.available((String) args.get("sku")))
    .build();

prompt.addTool(stockTool);
```

构建细节见 [Tool 构建](./tool-build.md)。

## 控制工具选择

`Prompt.setToolChoice(...)` 用于把工具选择策略交给具体模型协议。可用值及语义取决于模型服务商；使用前应检查对应模型是否支持 Tool 以及它接受的 `tool_choice` 格式。

工具很多时，不要把全部 Schema 都发送给模型：按请求使用 [ToolGroup](./tool-group.md)，或让模型通过 [ToolSearch](./tool-search.md) 渐进发现工具。

## 安全与错误处理

Tool 是实际业务执行入口，不能把模型参数当作可信输入：

1. 在工具或 [ToolInterceptor](./tool-interceptor.md) 中做权限、租户和参数校验。
2. 对删除、付款、发信等副作用操作增加人工确认或幂等键。
3. 不向模型返回堆栈、密钥和内部连接信息。
4. 对超时和业务错误返回稳定、可解释的结构，让模型能够修正参数或向用户说明。
5. 不要盲目并行执行多个 `ToolCall`；先判断工具之间是否存在顺序和副作用依赖。

## 常见问题

### 为什么 `hasToolCalls()` 为 false？

确认工具已经添加到本次 `Prompt`，模型配置声明支持 Tool，并且工具名称、描述和参数足够明确。模型也可能判断无需调用工具。

### 为什么有 ToolCall，却没有执行任何工具？

`AiMessageResponse` 按工具名从原 Prompt 查找实现。若工具未注册、名称不一致，`getToolExecutors()` 会跳过该调用。

### 为什么第二次请求无法理解工具结果？

应先加入模型返回的 `AiMessage`，再加入带正确 `toolCallId` 的 `ToolMessage`。只回传结果文本会丢失调用关联。

### 工具结果会自动写入 Memory 吗？

不会。示例中的 `prompt.addMessage(...)` 和 `addMessages(...)` 是应用显式写入。

## 下一步

- [Tool 构建](./tool-build.md)：选择注解扫描、Map Builder 或类型化 Builder。
- [Tool 拦截器](./tool-interceptor.md)：加入权限、审计和缓存。
- [MCP 调用](./mcp.md)：把外部 MCP Server 的能力作为 Tool 使用。

---
title: ToolGroup 工具组
description: 使用 ToolGroup 按用户输入、账号、模型和业务上下文动态挂载工具与系统提示词，减少无关 Tool 定义带来的 Token 消耗。
---

# ToolGroup 工具组

## 概述

`ToolGroup` 用于把一组相关 Tool、系统提示词和匹配策略组合成一个可按请求激活的工具组。

一个 Prompt 可以配置多个 ToolGroup。每次请求时，框架根据当前 `ChatContext` 逐个执行 Matcher：

- 匹配成功：把该组的 Tool 和系统提示词加入本次模型请求。
- 匹配失败：该组不会影响本次请求。
- 多个组匹配成功：所有命中的组会按添加顺序合并。

```text
Prompt
  ├─ 天气工具组 ── 匹配“天气” ──> get_weather + 天气查询指令
  ├─ 订单工具组 ── 匹配“订单” ──> get_order + 订单处理指令
  └─ 管理工具组 ── 匹配管理员 ──> admin_tools + 管理员指令
```

ToolGroup 适合工具数量较多、但单次请求通常只会使用其中一部分的应用。未命中的 Tool 定义不会发送给模型，可以减少无关工具描述占用的 Token，也能降低模型误选工具的概率。

::: info Tool 与 ToolGroup 的区别
直接通过 `prompt.addTool(...)` 添加的 Tool 始终随当前 Prompt 发送。通过 `prompt.addToolGroup(...)` 添加的 Tool，只有所属工具组匹配成功时才会发送。
:::

## 快速上手

下面创建一个天气工具组。只有用户输入包含“天气”时，框架才会挂载天气 Tool 和对应的系统提示词。

### 1. 创建 Tool

```java
Tool weatherTool = Tool.builder()
    .name("get_weather")
    .description("查询指定城市的实时天气")
    .addParameter(Parameter.builder()
        .name("city")
        .type("string")
        .description("城市名称")
        .required(true)
        .build())
    .function(args -> {
        String city = String.valueOf(args.get("city"));
        return weatherService.query(city);
    })
    .build();
```

有关 Tool 的注解扫描、参数定义和 Builder 用法，请参考 [Tool 构建](./tool-build.md)。

### 2. 创建 ToolGroup

```java
ToolGroup weatherGroup = ToolGroup.builder("weather")
    .description("查询实时天气、温度和降雨情况")
    .systemPrompt("涉及天气问题时，应优先调用天气工具获取实时数据。")
    .addTool(weatherTool)
    .matcher(ToolGroupMatchers.promptContains("天气"))
    .build();
```

### 3. 添加到 Prompt

```java
SimplePrompt prompt = new SimplePrompt("上海今天的天气怎么样？");
prompt.addToolGroup(weatherGroup);

AiMessageResponse response = chatModel.chat(prompt);
```

本次输入包含“天气”，因此模型请求会包含 `get_weather`。如果输入改为“介绍一下上海”，天气工具组不会命中，请求中也不会携带这个 Tool。

ToolGroup 的解析由框架自动完成，不需要手动调用 Resolver 或添加拦截器。

## 完整 Demo

下面构建一个同时支持天气查询和订单查询的客服 Prompt。一次请求可以配置两个工具组，但只挂载与当前问题相关的工具。

### 定义工具

```java
Tool weatherTool = Tool.builder()
    .name("get_weather")
    .description("查询城市实时天气")
    .addParameter(Parameter.builder()
        .name("city")
        .type("string")
        .description("城市名称")
        .required(true)
        .build())
    .function(args -> {
        String city = String.valueOf(args.get("city"));
        return weatherService.query(city);
    })
    .build();

Tool orderTool = Tool.builder()
    .name("get_order")
    .description("根据订单号查询订单状态")
    .addParameter(Parameter.builder()
        .name("orderNo")
        .type("string")
        .description("订单号")
        .required(true)
        .build())
    .function(args -> {
        String orderNo = String.valueOf(args.get("orderNo"));
        return orderService.findByOrderNo(orderNo);
    })
    .build();
```

### 定义工具组

```java
ToolGroup weatherGroup = ToolGroup.builder("weather")
    .description("查询实时天气、温度和降雨情况")
    .systemPrompt(
        "回答天气问题前必须查询实时天气，不要使用过时信息。"
    )
    .addTool(weatherTool)
    .matcher(ToolGroupMatchers.promptContains(
        Arrays.asList("天气", "温度", "下雨"),
        true
    ))
    .build();

ToolGroup orderGroup = ToolGroup.builder("order")
    .description("查询订单状态、物流和配送进度")
    .systemPrompt(
        "处理订单问题时，只能查询当前账号下的订单。"
    )
    .addTool(orderTool)
    .matcher(context ->
        context.getAccountId() != null
            && ToolGroupMatchers.promptContains("订单", "物流")
                .matches(context)
    )
    .build();
```

### 添加工具组并请求模型

```java
SimplePrompt prompt = new SimplePrompt("帮我查询订单 AF20260001 的物流状态");
prompt.setSystemMessage("你是一名客服助手，请准确回答用户问题。");
prompt.addToolGroups(Arrays.asList(weatherGroup, orderGroup));

ChatOptions options = ChatOptions.builder()
    .contextAccountId("user-1001")
    .build();

AiMessageResponse response = chatModel.chat(prompt, options);
```

本次请求的结果是：

- `orderGroup` 命中，挂载 `get_order`。
- `orderGroup` 的系统提示词追加到原有系统提示词之后。
- `weatherGroup` 未命中，`get_weather` 不会进入请求。

如果用户同时询问“订单目的地的天气”，两个组都可以命中，并同时挂载两个 Tool。

## 典型场景

### 按关键词匹配

`promptContains(...)` 检查 Prompt 中最后一条用户消息。任意关键词出现即匹配成功：

```java
.matcher(ToolGroupMatchers.promptContains(
    "天气",
    "温度",
    "空气质量"
))
```

需要忽略大小写时，使用 Collection 重载：

```java
.matcher(ToolGroupMatchers.promptContains(
    Arrays.asList("weather", "temperature"),
    true
))
```

### 按正则表达式匹配

```java
ToolGroup trackingGroup = ToolGroup.builder("tracking")
    .addTool(trackingTool)
    .matcher(ToolGroupMatchers.promptMatches(
        "(?i)(订单|快递|物流).*(查询|状态|到哪)"
    ))
    .build();
```

`promptMatches(...)` 使用 `Pattern.find()` 判断，因此正则不需要匹配整段用户输入。

### 按用户或租户匹配

自定义 Matcher 可以读取 `ChatContext` 中的账号、会话及业务属性：

```java
ToolGroup premiumGroup = ToolGroup.builder("premium-service")
    .systemPrompt("当前用户是高级会员，可以使用高级分析能力。")
    .addTools(premiumTools)
    .matcher(context ->
        context.getAccountId() != null
            && "premium".equals(context.getAttribute("plan"))
            && Boolean.TRUE.equals(context.getAttribute("analysisEnabled"))
    )
    .build();
```

业务属性可以通过 `ChatOptions` 传入：

```java
Map<String, Object> attributes = new HashMap<>();
attributes.put("plan", "premium");
attributes.put("analysisEnabled", true);

ChatOptions options = ChatOptions.builder()
    .contextAccountId("user-1001")
    .contextAttributes(attributes)
    .build();
```

### 按模型匹配

```java
.matcher(context -> {
    String model = context.getOptions().getModelOrDefault(
        context.getConfig().getModel()
    );
    return model != null && model.startsWith("qwen");
})
```

适合只向支持特定参数格式、并行工具调用或某类 Tool 能力的模型挂载工具。

### 始终挂载一组工具

没有配置 Matcher 时，ToolGroup 默认使用 `ToolGroupMatchers.always()`：

```java
ToolGroup basicGroup = ToolGroup.builder("basic")
    .systemPrompt("优先使用基础工具完成精确计算。")
    .addTools(basicTools)
    .build();
```

如果一组工具始终生效，也可以直接添加到 Prompt。使用 ToolGroup 的价值主要在于把相关工具和系统提示词作为一个整体复用和管理。

### 多个工具组组合生效

```java
prompt.addToolGroups(Arrays.asList(
    weatherGroup,
    orderGroup,
    knowledgeGroup
));
```

ToolGroup 不是互斥选择。框架会评估每个组，并合并全部命中的 Tool 和系统提示词。如果业务要求只能命中一个组，应在 Matcher 中自行定义互斥条件。

### 根据拦截器补充的上下文匹配

应用拦截器可以先识别租户、用户权限或请求意图，再把结果写入 `ChatContext`：

```java
public AiMessageResponse intercept(
    BaseChatModel<?> chatModel,
    ChatContext context,
    SyncChain chain
) {
    context.addAttribute("department", resolveDepartment(context));
    return chain.proceed(chatModel, context);
}
```

ToolGroup Matcher 随后可以读取该属性：

```java
.matcher(context ->
    "finance".equals(context.getAttribute("department"))
)
```

框架默认在普通应用拦截器之后解析 ToolGroup，因此这种方式不需要手动调用 `ToolGroupPromptResolver`。有关执行顺序，请参考 [对话拦截器](./chat-interceptor.md#执行顺序)。

## 进阶使用

### ToolGroup 结构

ToolGroup 通过 Builder 创建，构建后内容不可变：

| 属性 | 是否必填 | 说明 |
| --- | --- | --- |
| `name` | 是 | 工具组的稳定名称，用于识别和诊断 |
| `description` | 否 | 描述工具组解决的问题，可用于配置展示和语义路由 |
| `systemPrompt` | 否 | 工具组命中后追加的系统提示词 |
| `tools` | 否 | 工具组包含的 Tool 列表 |
| `matcher` | 否 | 是否命中当前请求，默认为 `always()` |

```java
ToolGroup group = ToolGroup.builder("group-name")
    .description("工具组的能力描述")
    .systemPrompt("命中后追加的系统指令")
    .addTool(toolA)
    .addTools(Arrays.asList(toolB, toolC))
    .matcher(context -> true)
    .build();
```

`name` 不能为空。`description` 只描述工具组的用途，不会自动追加到系统提示词，也不会作为工具定义发送给业务模型。ToolGroup 可以只包含 Tool、只包含系统提示词，或者同时包含两者。

### ToolGroupMatcher

`ToolGroupMatcher` 是一个函数式接口：

```java
@FunctionalInterface
public interface ToolGroupMatcher {
    boolean matches(ChatContext context);
}
```

Matcher 可以访问完整的 `ChatContext`，包括：

- 当前 `Prompt` 和最后一条用户消息
- `ChatOptions` 和本次使用的模型
- `accountId`、`conversationId`、`botId`、`turnId`
- 应用通过 Attributes 传入或由前置拦截器写入的业务数据
- URL、Header 和重试配置等 `ChatRequestSpec` 信息

Matcher 应保持轻量且无副作用。不要在 Matcher 内执行网络请求，也不要修改 Context；复杂数据应由更早执行的拦截器准备。

### 内置 Matcher

| 方法 | 说明 |
| --- | --- |
| `always()` | 始终匹配，也是默认策略 |
| `promptContains(String...)` | 最后一条用户消息包含任意关键词时匹配 |
| `promptContains(Collection, boolean)` | 支持关键词集合和忽略大小写 |
| `promptMatches(String)` | 最后一条用户消息匹配正则时生效 |
| `promptMatches(Pattern)` | 使用预编译 Pattern 匹配 |

组合条件可以直接使用 Lambda：

```java
.matcher(context ->
    ToolGroupMatchers.promptContains("报表").matches(context)
        && "admin".equals(context.getAttribute("role"))
)
```

### 工具合并规则

ToolGroup 解析后，工具按以下顺序合并：

1. Prompt 直接添加的 Tool。
2. 按 ToolGroup 添加顺序合并所有命中组中的 Tool。

Tool 名称是合并键。如果出现同名 Tool，后合并的 Tool 会替换先前的 Tool。建议在应用范围内保持 Tool 名称唯一，避免依赖覆盖行为。

```java
prompt.addTool(baseSearchTool);
prompt.addToolGroup(advancedSearchGroup);
```

如果 `advancedSearchGroup` 命中，且其中也存在同名 Tool，则工具组中的实现会覆盖 `baseSearchTool`。

### 系统提示词合并规则

命中多个 ToolGroup 时，系统提示词按工具组添加顺序使用两个换行符连接：

```text
Prompt 原有系统提示词

第一个命中组的系统提示词

第二个命中组的系统提示词
```

如果 Prompt 原本没有 `SystemMessage`，框架会创建一个并放在消息列表开头。空白的 `systemPrompt` 会被忽略。

### Prompt 隔离

ToolGroup 解析不会直接修改原始 Prompt。框架为当前请求创建解析后的 Prompt 快照，再挂载匹配的 Tool 和系统提示词。

这意味着同一个 `MemoryPrompt` 可以连续处理不同意图：

```text
第 1 轮：“查询上海天气” → 挂载天气工具
第 2 轮：“和我打个招呼” → 不挂载天气工具
```

内置 Prompt Matcher 只检查最后一条用户消息，不会因为历史消息中出现过关键词而让工具组持续生效。

### 与 ChatInterceptor 的执行顺序

框架通过内置的 `tool-group-resolver` Registration 自动解析 ToolGroup，其默认顺序是：

```java
ChatInterceptorOrders.REQUEST_PREPARATION  // 10000
```

默认 `order` 为 `0` 的应用拦截器会先执行，因此可以修改 Prompt、Options 或 Context Attributes，ToolGroup Matcher 会看到这些修改。

如果自定义拦截器需要读取已经解析完成的 Prompt，可以将它放在 ToolGroup 之后：

```java
ChatInterceptorRegistration.builder(
        "resolved-prompt-audit",
        new ResolvedPromptAuditInterceptor()
    )
    .order(ChatInterceptorOrders.REQUEST_PREPARATION + 100)
    .build();
```

ToolGroup 解析后再添加新的 ToolGroup，不会触发第二次解析。

## 最佳实践

1. 按业务能力划分工具组，例如天气、订单、知识库，而不是为每个 Tool 单独创建一个组。
2. Matcher 使用明确、稳定的条件，避免过于宽泛的关键词导致无关工具被挂载。
3. Tool 名称在应用范围内保持唯一，不依赖同名覆盖。
4. 系统提示词只描述该组工具的使用约束，不重复主 Prompt 中的通用角色设定。
5. 自定义 Matcher 保持只读、快速，不执行数据库或远程调用。
6. 用户权限、租户配置等复杂信息由前置 ChatInterceptor 写入 `ChatContext`。
7. ToolGroup 构建后可以安全复用，不要在其中保存请求级可变状态。
8. 为命中和未命中路径分别编写测试，确认请求只携带预期 Tool。

## 实现说明

当 Prompt 包含 ToolGroup 时，框架内置的 `ToolGroupChatInterceptor` 会调用 `ToolGroupPromptResolver`。Resolver 根据当前 `ChatContext` 计算命中的组，并创建一个请求级 Prompt 快照。

只有解析后 Prompt 中实际存在的 Tool 和系统提示词才会进入模型请求。ToolGroup 配置本身不会作为额外字段发送给模型。

同步和流式请求使用相同的 ToolGroup 解析规则，不需要分别配置。

## 常见问题

### 为什么 ToolGroup 没有生效？

先确认 ToolGroup 已添加到本次使用的 Prompt，再检查 Matcher 是否读取了正确的最后一条用户消息或 Context 属性。如果依赖前置拦截器写入数据，还要确认其 `order` 小于 `REQUEST_PREPARATION`。

### 为什么没有配置 Matcher 也生效了？

ToolGroup 的默认 Matcher 是 `ToolGroupMatchers.always()`。需要条件激活时，应显式调用 `.matcher(...)`。

### 一次请求可以命中多个 ToolGroup 吗？

可以。所有命中的组都会合并，并不是只选择第一个。如果需要互斥，请在 Matcher 中定义互斥条件。

### ToolGroup 会修改原始 Prompt 吗？

不会。框架使用请求级 Prompt 快照，因此一次匹配结果不会污染后续请求。

### ToolGroup 能只追加系统提示词而不添加 Tool 吗？

可以。`tools` 和 `systemPrompt` 都是可选内容。工具组也可以只负责按条件补充系统指令。

### ToolGroup 能否用于 MemoryPrompt？

可以。内置 Prompt Matcher 检查最后一条用户消息，每一轮都会重新计算匹配结果。

### 是否需要手动调用 ToolGroupPromptResolver？

通常不需要。只要把 ToolGroup 添加到 Prompt，框架会在 Chat 请求链中自动完成解析。

## 相关文档

- [Tool 工具调用](./tool.md)
- [Tool 构建](./tool-build.md)
- [Prompt 提示词](./prompt.md)
- [对话上下文](./chat-context.md)
- [对话拦截器](./chat-interceptor.md)

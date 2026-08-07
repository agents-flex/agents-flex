---
title: ToolSearchTool 工具搜索
description: 使用 ToolSearchTool 按需搜索并渐进式暴露工具，降低中大型工具目录的 Token 消耗和模型选错工具的概率。
---

# ToolSearchTool 工具搜索

## 概述

随着应用接入 MCP、内部 API、数据库、消息平台和业务服务，可用 Tool 很容易从几个增长到几十个甚至上百个。如果把全部 Tool 定义放进每次模型请求，会产生三个问题：

1. **上下文成本增加**：Tool 的名称、描述和参数 Schema 会持续占用 Token。
2. **工具选择变难**：大量名称或用途相近的 Tool 会增加模型误选概率。
3. **无关信息干扰**：用户只需要查询天气时，没有必要同时发送邮件、订单、报表等工具定义。

`ToolSearchTool` 使用渐进式工具披露解决这个问题：第一轮只向模型提供一个工具搜索入口；模型描述所需能力后，框架从工具目录中找到相关 Tool，并只在下一轮加入最近一次搜索命中的完整 Tool 定义。

```text
全部可搜索 Tool
        ↓ 注册到 ToolSearchProvider，不发送给模型
Prompt 常驻 Tool + toolSearch
        ↓ 第一轮模型请求
模型调用 toolSearch(query = "查询城市天气")
        ↓ 内存或自定义 Provider 检索
Prompt 常驻 Tool + toolSearch + getWeather
        ↓ 下一轮模型请求
模型调用 getWeather(city = "上海")
```

::: tip 适用场景
当应用有几十到几百个 Tool，且单次任务通常只需要其中少量 Tool 时，ToolSearchTool 最有价值。只有几个 Tool，或者所有 Tool 每轮都会使用时，直接添加到 Prompt 更简单，也能省去一次搜索调用。
:::

默认实现使用内存保存元数据并进行 O(n) 加权扫描，不依赖 Lucene、Elasticsearch、向量数据库或 Embedding 模型。对于常见的应用级工具数量，这通常比维护外部搜索基础设施更合适。

## 适用场景

- MCP 或插件平台在运行时注册了大量长尾工具。
- 工具目录会增长，无法靠固定关键词规则长期维护 ToolGroup。
- 希望第一轮只发送一个搜索工具，在后续轮次渐进披露完整 Schema。
- 需要把工具元数据放入数据库、全文索引或其他自定义搜索后端。

ToolSearch 会多一次模型与搜索工具的往返。少量高频工具仍应直接加入 Prompt。

## 快速开始

以下示例直接使用 `ChatModel`。如果应用通过 `AgentRunner` 执行，请使用后面的
[接入 AgentRunner](#接入-agentrunner)。

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-toolsearch</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

### 2. 创建业务 Tool

```java
Tool weatherTool = Tool.builder()
    .name("getWeather")
    .description("查询指定城市的实时天气和未来预报")
    .addParameter(Parameter.builder()
        .name("city")
        .type("string")
        .description("城市名称")
        .required(true)
        .build())
    .function(args -> weatherService.query(String.valueOf(args.get("city"))))
    .build();

Tool emailTool = Tool.builder()
    .name("sendEmail")
    .description("向指定邮箱发送邮件")
    .addParameter(Parameter.builder()
        .name("address")
        .type("string")
        .description("收件人邮箱")
        .required(true)
        .build())
    .addParameter(Parameter.builder()
        .name("content")
        .type("string")
        .description("邮件正文")
        .required(true)
        .build())
    .function(args -> emailService.send(
        String.valueOf(args.get("address")),
        String.valueOf(args.get("content"))
    ))
    .build();
```

### 3. 创建 Prompt 和 ToolSearchTool

```java
MemoryPrompt prompt = new MemoryPrompt();
prompt.setSystemMessage(
    "当当前可见工具无法完成任务时，使用 toolSearch 搜索需要的能力。"
);
prompt.addUserMessage("查询上海天气，然后把结果发送到我的邮箱");

ToolSearchTool toolSearch = ToolSearchTool.builder()
    .addTools(Arrays.asList(weatherTool, emailTool))
    .build();

prompt.addTool(toolSearch);
```

`ToolSearchTool` 实现了 `ChatInterceptorProvider`，并通过 `ChatInterceptorRegistration` 声明
请求准备阶段的执行顺序。因此，普通 ChatModel 场景下只要把它加入
`Prompt`，ChatModel 就会在当前请求中自动启用 `ToolSearchChatInterceptor`，不需要再手动调用
`chatModel.addInterceptor(...)`：

```java
prompt.addTool(toolSearch);
chatModel.chat(prompt);
```

拦截器只加入当前请求的责任链，不会修改 ChatModel 的共享配置。原有的显式注册方式仍然兼容；
ToolSearch 的请求快照具备幂等性，但新代码不再需要重复注册。

这里有三个关键点：

- `.addTools(...)` 注册的是**可搜索 Tool**，初始不会发送给模型。
- `prompt.addTool(toolSearch)` 只把搜索入口加入 Prompt。
- `ToolSearchChatInterceptor` 在每次模型调用前读取最近一次搜索结果，并创建请求级 Prompt 快照。

### 4. 执行工具调用循环

```java
AiMessageResponse response = chatModel.chat(prompt);

for (int i = 0; i < 10 && response.hasToolCalls(); i++) {
    prompt.addMessage(response.getMessage());
    prompt.addMessages(response.executeToolCallsAndGetToolMessages());
    response = chatModel.chat(prompt);
}

String answer = response.getMessage().getContent();
```

`response.executeToolCallsAndGetToolMessages()` 执行 `toolSearch` 后会产生包含工具名称的 ToolMessage。
应用必须像示例一样依次保存 AiMessage 和 ToolMessage；下一次 `chatModel.chat(prompt)` 时，拦截器会
按 toolCallId 关联这两条消息，并把命中 Tool 的完整定义加入本次请求快照。原始 Prompt 不会被修改。

::: warning 必须继续下一轮模型调用
`toolSearch` 只发现 Tool，不执行目标业务 Tool。执行搜索后必须把 Tool Message 加回 Prompt 并再次调用 ChatModel，模型才能选择刚刚发现的 Tool。
:::

## 接入 AgentRunner

Agent 模式使用 `ToolSearchAgentMiddleware`。Middleware 会自动完成三件事：

1. 在每次模型调用前把 `toolSearch` 和当前 Turn 最近一次命中的 Tool 加入 Prompt。
2. 搜索完成后把命中的 Tool 名称保存到 Turn metadata，下一次模型调用只披露这些 Tool。
3. 为 Runner 提供动态工具解析器，使恢复后的 Turn 仍能执行已激活的 Tool。

```java
ToolSearchTool toolSearch = ToolSearchTool.builder()
    .addTools(Arrays.asList(weatherTool, emailTool))
    .build();

Agent agent = Agent.builder("assistant")
    .chatModel(chatModel)
    .middleware(ToolSearchAgentMiddleware.of(toolSearch))
    .build();

AgentTurn turn = new AgentRunner().run(
    agent,
    "查询上海天气，然后把结果发送到我的邮箱"
);
```

可搜索 Tool 不需要再通过 `Agent.builder().tool(...)` 注册。需要始终可见的高频 Tool 可以正常注册
到 Agent；它们仍由 Agent 直接解析，不经过搜索激活。

最近一次搜索命中的名称保存在 metadata 的
`agentsflex.toolsearch.activeToolNames` 中，并随 Snapshot 持久化。新的搜索结果会整体替换旧结果；
Runner 从 Snapshot 恢复 Turn 后，Middleware 会根据这些名称重新披露并解析 Tool。Snapshot 只保存
名称，不保存 Tool 实例，因此恢复时必须加载包含同一 Middleware 和工具目录的 Agent 版本。

## 两类 Tool

ToolSearchTool 明确区分常驻 Tool 和可搜索 Tool。

### 常驻 Tool

开发者直接添加到 Prompt 的 Tool 始终发送给模型，也不会进入搜索 Provider：

```java
prompt.addTool(currentTimeTool);
prompt.addTool(askUserTool);
```

常驻 Tool 适合：

- 几乎每轮都可能使用的基础能力。
- 安全确认、用户交互等流程控制工具。
- 不希望经过搜索、必须始终可见的工具。

`ToolSearchChatInterceptor` 每次都从当前 Prompt 创建请求快照，因此后续增加或移除常驻 Tool 会在
下一次模型调用时自然生效，不需要同步或重置 ToolSearchTool。

### 可搜索 Tool

通过 Builder 添加的 Tool 会进入搜索目录：

```java
ToolSearchTool toolSearch = ToolSearchTool.builder()
    .addTool(weatherTool)
    .addTools(orderTools)
    .build();

prompt.addTool(toolSearch);
```

它们初始不在 Prompt 中，只有最近一次搜索命中的 Tool 才会发送给模型。

## 最近一次搜索结果

ToolSearchTool 本身不保存搜索状态。普通 ChatModel 模式下，拦截器只解析消息链中最近一次
`toolSearch` 调用的结果，因此每次搜索都会整体替换上一次披露的 Tool：

```text
第一次搜索 weather
Prompt = 常驻 Tool + toolSearch + getWeather

第二次搜索 email
Prompt = 常驻 Tool + toolSearch + sendEmail

第三次搜索无结果
Prompt = 常驻 Tool + toolSearch
```

搜索无结果、ToolMessage 无效或最近一次搜索尚未完成时，请求快照中不会继续沿用更早的结果。
这样既能防止工具目录不断累积，也不需要在共享 Tool 实例上维护可变运行状态。

## 搜索结果为什么只返回名称

模型调用 `toolSearch` 后，Tool Message 返回类似下面的内容：

```json
["getWeather", "getWeatherForecast"]
```

名称是 ToolSearchTool 激活 Tool 的稳定引用。模型不需要根据名称猜测参数，因为下一轮请求会携带命中 Tool 的完整名称、描述和参数 Schema。只返回名称可以避免在 Tool Message 中重复发送完整定义。

普通 ChatModel 模式只需把搜索 AiMessage 与 ToolMessage 加入同一个
Prompt 的消息历史；AgentRunner 模式则由 `ToolSearchAgentMiddleware` 自动处理。

## 默认内存搜索

没有配置 Provider 时，Builder 自动使用 `InMemoryToolSearchProvider`：

```java
ToolSearchTool toolSearch = ToolSearchTool.builder()
    .addTools(applicationTools)
    .build();
```

内存 Provider 使用 `ConcurrentHashMap` 保存 `ToolInfo`，搜索时对当前元数据快照做加权扫描。搜索范围包括：

- Tool 名称
- Tool 描述
- 参数名称和参数描述
- Category
- Tags

名称匹配权重最高，其次是分类和标签，再其次是描述与参数。结果按分数降序返回，默认最多返回 5 个。

可以在模型搜索时传入：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `query` | 是 | 描述所需能力的自然语言查询 |
| `maxResults` | 否 | 最大返回数量，默认 5 |
| `category` | 否 | 精确匹配 Tool 分类 |

内存实现是词法检索，不是语义检索。查询和 Tool 描述使用完全不同的表达时，可能无法命中；此时可以完善 Tool 描述和标签，或者替换 Provider。

## 补充 Tool 元数据

`ToolInfo.from(tool)` 会读取 Tool 名称、描述和参数。Category、Tags 和自定义 Metadata 可以额外设置：

```java
ToolInfo weatherInfo = ToolInfo.from(weatherTool);
weatherInfo.setCategory("weather");
weatherInfo.setTags(Arrays.asList("forecast", "temperature", "rain"));

Map<String, Object> metadata = new HashMap<>();
metadata.put("owner", "weather-team");
metadata.put("version", "v2");
weatherInfo.setMetadata(metadata);

ToolSearchTool toolSearch = ToolSearchTool.builder()
    .addTool(weatherTool, weatherInfo)
    .build();
```

`ToolInfo` 只保存可序列化的检索信息，不保存 Tool 的执行回调。实际可执行 Tool 始终由 `ToolSearchManager` 在本地维护。

## 自定义 ToolSearchProvider

工具目录达到更大规模、需要语义召回，或者元数据必须集中管理时，可以实现 `ToolSearchProvider`：

```java
public class DatabaseToolSearchProvider implements ToolSearchProvider {

    @Override
    public void save(ToolInfo toolInfo) {
        repository.save(toolInfo);
    }

    @Override
    public ToolInfo findByName(String name) {
        return repository.findByName(name);
    }

    @Override
    public List<ToolInfo> findAll() {
        return repository.findAll();
    }

    @Override
    public List<ToolSearchResult> search(ToolSearchRequest request) {
        return repository.search(
            request.getQuery(),
            request.getMaxResults(),
            request.getCategory()
        );
    }

    @Override
    public boolean remove(String name) {
        return repository.remove(name);
    }

    @Override
    public void clear() {
        repository.clear();
    }
}
```

通过 Builder 注入：

```java
ToolSearchTool toolSearch = ToolSearchTool.builder()
    .provider(new DatabaseToolSearchProvider())
    .addTools(applicationTools)
    .build();
```

自定义 Provider 只负责 Tool 元数据的保存和检索。Provider 返回的名称必须能在当前 `ToolSearchManager` 中解析到可执行 Tool；只有远程元数据、没有本地执行回调的结果会被忽略。

## Manager 与隔离

默认情况下，每次 Builder 构建都会创建独立的 `ToolSearchManager` 和 Provider：

```java
ToolSearchTool first = ToolSearchTool.builder()
    .addTools(firstTools)
    .build();

ToolSearchTool second = ToolSearchTool.builder()
    .addTools(secondTools)
    .build();
```

两个实例的目录互不影响。ToolSearchTool 不保存 Prompt 或最近搜索结果，因此同一个实例可以安全地
加入多个 Prompt；每个 Prompt 的搜索状态都来自自己的消息历史。并发复用时，自定义 Manager 和
Provider 也必须是线程安全的；默认内存实现支持并发访问。

确实需要共享一个工具目录时，可以显式复用 Manager：

```java
ToolSearchManager manager = new ToolSearchManager();
manager.registerAll(sharedTools);

ToolSearchTool first = ToolSearchTool.builder()
    .manager(manager)
    .build();

ToolSearchTool second = ToolSearchTool.builder()
    .manager(manager)
    .build();
```

`.manager(...)` 和 `.provider(...)` 不能同时配置，因为 Manager 已经持有 Provider。

## ToolSearchTool 与 ToolGroup 的区别

两者都能减少发送给模型的 Tool 数量，但决策者、执行时机和适用问题不同。

| 对比项 | ToolGroup | ToolSearchTool |
| --- | --- | --- |
| 决策者 | 应用代码中的 `ToolGroupMatcher` | 大模型通过 `toolSearch` 查询能力 |
| 决策时机 | 第一次模型调用之前 | 模型先调用搜索 Tool，下一轮再获得目标 Tool |
| 匹配依据 | 最后一条用户消息、账号、租户、模型、Context 属性等 | 模型生成的自然语言 query，与 Tool 元数据匹配 |
| 是否增加模型往返 | 不增加 | 通常增加一次搜索 Tool Call |
| Tool 粒度 | 预先设计的业务工具组 | 从统一目录返回最近一次 Top-K Tool |
| 系统提示词 | 命中组可以追加 `systemPrompt` | 不追加业务系统提示词，依靠 Tool description 指导搜索 |
| Prompt 处理 | 创建当前请求的解析快照，不修改原 Prompt | 创建当前请求的解析快照，不修改原 Prompt |
| 状态 | 每个请求重新运行 Matcher | 普通模式读取消息历史；Agent 模式读取 Turn metadata |
| 典型优势 | 规则确定、无需额外模型调用、权限控制清晰 | 无需穷举路由规则，适合长尾能力和较大的动态目录 |

### 什么时候选择 ToolGroup

以下情况优先使用 [ToolGroup](./tool-group.md)：

- 可以用稳定规则判断工具是否应该出现，例如关键词、用户角色、租户或模型能力。
- 工具天然分成少量明确的业务域，例如天气、订单、财务和管理员工具。
- 必须在第一次模型请求中提供目标 Tool，不希望增加搜索往返。
- Tool 激活同时需要追加业务系统提示词或权限约束。

例如只有管理员才能看到后台工具：

```java
ToolGroup adminGroup = ToolGroup.builder("admin")
    .addTools(adminTools)
    .systemPrompt("管理员操作必须记录审计日志。")
    .matcher(context -> "admin".equals(context.getAttribute("role")))
    .build();

prompt.addToolGroup(adminGroup);
```

这种条件不应该交给模型搜索决定，应用应在模型调用前完成权限判断。

### 什么时候选择 ToolSearchTool

以下情况优先使用 ToolSearchTool：

- Tool 有几十到几百个，难以维护完整的关键词路由规则。
- Tool 来自多个 MCP Server 或插件，目录会动态变化。
- 用户意图具有长尾表达，模型比静态规则更适合描述所需能力。
- 单次任务只使用少量 Tool，可以接受一次额外的工具搜索往返。

### 组合使用

ToolGroup 和 ToolSearchTool 可以组合：

- Prompt 直接添加始终可见的基础 Tool。
- ToolGroup 按权限、租户和明确业务规则挂载确定性 Tool。
- ToolSearchTool 管理剩余的长尾工具目录。

```java
prompt.addTool(currentTimeTool);
prompt.addToolGroup(adminGroup);

ToolSearchTool toolSearch = ToolSearchTool.builder()
    .addTools(longTailTools)
    .build();

prompt.addTool(toolSearch);
chatModel.chat(prompt);
```

不要把权限控制只放在 ToolSearchTool 的描述或搜索标签中。Tool 搜索解决的是“找到什么能力”，ToolGroup 和业务拦截器解决的是“当前请求是否允许获得这项能力”。

## 最佳实践

1. 把高频基础 Tool 直接加入 Prompt，把长尾 Tool 放入搜索目录。
2. Tool 名称和描述要准确说明动作、对象和边界，避免使用含糊的 `handle`、`process` 等名称。
3. 参数描述同样参与默认搜索，应写清业务含义而不是只重复参数名。
4. 对有明确业务分类的 Tool 设置 Category 和 Tags，提高召回稳定性。
5. 控制 `maxResults`，通常返回 3 到 5 个候选即可。
6. 使用 `MemoryPrompt` 保存搜索调用、搜索结果和后续 Tool Call 的完整消息链。
7. 为搜索命中、无结果、连续搜索替换和常驻 Tool 保留分别编写测试。
8. 权限和租户隔离应在注册 Tool 或执行 Tool 前强制校验，不依赖模型自行遵守描述。

## 常见问题

### 为什么模型只得到 Tool 名称？

名称用于标识本次激活的 Tool。下一轮模型请求会收到对应的完整 Tool 定义，因此不需要在搜索结果中重复传输描述和参数。

### 为什么搜索到新 Tool 后，旧 Tool 不见了？

这是预期行为。拦截器只使用最近一次搜索结果，防止长对话不断累积工具定义。常驻 Tool 不受影响。

### 为什么 Prompt 中直接添加的 Tool 没有进入搜索结果？

Prompt 直接添加的是常驻 Tool，它已经对模型可见，没有必要再次进入搜索目录。只有 Builder `.addTool(...)` 和 `.addTools(...)` 注册的 Tool 可被搜索。

### 为什么自定义 Provider 返回了结果，但 Prompt 没有出现对应 Tool？

Provider 只保存元数据。请确认同名的可执行 Tool 已注册到当前 Manager。无法解析到本地 Tool 的远程结果会被忽略。

### 一个 ToolSearchTool 可以用于多个 Prompt 吗？

可以。ToolSearchTool 不持有 Prompt 和运行状态。普通模式下，每个 Prompt 必须保存自己的完整工具调用
消息链；拦截器会分别解析。不同目录可以构建不同实例，需要共享目录时可以共享 ToolSearchManager。
并发使用自定义 Manager 或 Provider 时，实现本身也必须保证线程安全。

### 少量 Tool 需要使用 ToolSearchTool 吗？

通常不需要。少量 Tool 直接发送给模型更简单，也不会增加搜索往返。ToolSearchTool 的价值来自减少中大型目录的上下文和选择噪声。

## 下一步

- [Tool 工具调用](./tool.md)
- [Tool 构建](./tool-build.md)
- [ToolGroup 工具组](./tool-group.md)
- [Prompt 提示词](./prompt.md)
- [Memory 记忆](./memory.md)
- [Tool 拦截器](./tool-interceptor.md)

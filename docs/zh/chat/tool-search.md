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

## 快速开始

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
    .prompt(prompt)
    .build();
```

这里有两个关键点：

- `.addTools(...)` 注册的是**可搜索 Tool**，初始不会发送给模型。
- `.prompt(prompt)` 把 ToolSearchTool 绑定到当前 Prompt，并把 `toolSearch` 加入可见工具。

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

`response.executeToolCallsAndGetToolMessages()` 执行 `toolSearch` 时，会同步修改绑定的 Prompt。下一次 `chatModel.chat(prompt)` 因此能看到搜索命中的完整 Tool 定义。

::: warning 必须继续下一轮模型调用
`toolSearch` 只发现 Tool，不执行目标业务 Tool。执行搜索后必须把 Tool Message 加回 Prompt 并再次调用 ChatModel，模型才能选择刚刚发现的 Tool。
:::

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

在 `ToolSearchTool` 构建后继续调用 `prompt.addTool(...)` 也有效。每次搜索、`reset()` 和 `unbind()` 前，ToolSearchTool 都会重新同步开发者维护的常驻 Tool，因此后加或后删的 Tool 不会被旧快照覆盖。

### 可搜索 Tool

通过 Builder 添加的 Tool 会进入搜索目录：

```java
ToolSearchTool toolSearch = ToolSearchTool.builder()
    .addTool(weatherTool)
    .addTools(orderTools)
    .prompt(prompt)
    .build();
```

它们初始不在 Prompt 中，只有最近一次搜索命中的 Tool 才会发送给模型。

## 最近一次搜索结果

ToolSearchTool 不累积历次搜索结果。每次搜索都会整体替换上一次动态加入的 Tool：

```text
第一次搜索 weather
Prompt = 常驻 Tool + toolSearch + getWeather

第二次搜索 email
Prompt = 常驻 Tool + toolSearch + sendEmail

第三次搜索无结果
Prompt = 常驻 Tool + toolSearch
```

这样可以防止长对话不断积累 Tool，最终重新退化成发送整个工具目录。

也可以显式清除当前搜索结果：

```java
toolSearch.reset();
```

`reset()` 只移除搜索发现的 Tool，不影响常驻 Tool 和 `toolSearch`。

## 搜索结果为什么只返回名称

模型调用 `toolSearch` 后，Tool Message 返回类似下面的内容：

```json
["getWeather", "getWeatherForecast"]
```

名称是 ToolSearchTool 激活 Tool 的稳定引用。模型不需要根据名称猜测参数，因为下一轮请求会携带命中 Tool 的完整名称、描述和参数 Schema。只返回名称可以避免在 Tool Message 中重复发送完整定义。

如果没有绑定 Prompt，名称不会自动扩展成下一轮的完整 Tool 定义。因此渐进式使用时，应通过 Builder 的 `.prompt(prompt)` 或构建后的 `bind(prompt)` 完成绑定。

## 默认内存搜索

没有配置 Provider 时，Builder 自动使用 `InMemoryToolSearchProvider`：

```java
ToolSearchTool toolSearch = ToolSearchTool.builder()
    .addTools(applicationTools)
    .prompt(prompt)
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
    .prompt(prompt)
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
    .prompt(prompt)
    .build();
```

自定义 Provider 只负责 Tool 元数据的保存和检索。Provider 返回的名称必须能在当前 `ToolSearchManager` 中解析到可执行 Tool；只有远程元数据、没有本地执行回调的结果会被忽略。

## Manager 与隔离

默认情况下，每次 Builder 构建都会创建独立的 `ToolSearchManager` 和 Provider：

```java
ToolSearchTool first = ToolSearchTool.builder()
    .addTools(firstTools)
    .prompt(firstPrompt)
    .build();

ToolSearchTool second = ToolSearchTool.builder()
    .addTools(secondTools)
    .prompt(secondPrompt)
    .build();
```

两个实例的目录和搜索结果互不影响。一个 ToolSearchTool 同一时间只能绑定一个 Prompt；尝试绑定第二个 Prompt 会抛出异常。

解除绑定时，原 Prompt 会恢复为当前常驻 Tool 集合：

```java
toolSearch.unbind();
toolSearch.bind(anotherPrompt);
```

确实需要共享一个工具目录时，可以显式复用 Manager：

```java
ToolSearchManager manager = new ToolSearchManager();
manager.registerAll(sharedTools);

ToolSearchTool first = ToolSearchTool.builder()
    .manager(manager)
    .prompt(firstPrompt)
    .build();

ToolSearchTool second = ToolSearchTool.builder()
    .manager(manager)
    .prompt(secondPrompt)
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
| Prompt 处理 | 创建当前请求的解析快照，不修改原 Prompt | 修改绑定的原 Prompt，为下一轮加入搜索结果 |
| 状态 | 每个请求重新运行 Matcher，无请求间发现状态 | 保存最近一次发现结果，下一次搜索时替换 |
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
    .prompt(prompt)
    .build();
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

这是预期行为。ToolSearchTool 只保留最近一次搜索结果，防止长对话不断累积工具定义。常驻 Tool 不受影响。

### 为什么 Prompt 中直接添加的 Tool 没有进入搜索结果？

Prompt 直接添加的是常驻 Tool，它已经对模型可见，没有必要再次进入搜索目录。只有 Builder `.addTool(...)` 和 `.addTools(...)` 注册的 Tool 可被搜索。

### 为什么自定义 Provider 返回了结果，但 Prompt 没有出现对应 Tool？

Provider 只保存元数据。请确认同名的可执行 Tool 已注册到当前 Manager。无法解析到本地 Tool 的远程结果会被忽略。

### 可以绑定多个 Prompt 吗？

不可以。一个 ToolSearchTool 只能绑定一个 Prompt。每个对话分别构建 ToolSearchTool，或者先调用 `unbind()` 再绑定另一个 Prompt。

### 少量 Tool 需要使用 ToolSearchTool 吗？

通常不需要。少量 Tool 直接发送给模型更简单，也不会增加搜索往返。ToolSearchTool 的价值来自减少中大型目录的上下文和选择噪声。

## 相关文档

- [Tool 工具调用](./tool.md)
- [Tool 构建](./tool-build.md)
- [ToolGroup 工具组](./tool-group.md)
- [Prompt 提示词](./prompt.md)
- [Memory 记忆](./memory.md)
- [Tool 拦截器](./tool-interceptor.md)

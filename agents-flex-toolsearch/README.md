# Agents-Flex Tool Search

`agents-flex-toolsearch` 为拥有几十到几百个 Tool 的应用提供按需搜索和渐进式工具披露能力。模型不必在每次请求中看到整个工具目录，而是先通过 `ToolSearchTool` 搜索所需能力，再在下一轮获得最近一次搜索命中的完整 Tool 定义。

默认实现把 Tool 元数据保存在内存中，并通过 O(n) 加权词法扫描完成搜索，不依赖 Lucene、Elasticsearch、向量数据库或 Embedding 模型。对于一般工程中的工具规模，内存搜索通常足够简单、直接；当工具达到更大规模或需要语义搜索时，可以替换 `ToolSearchProvider`。

## 解决什么问题

应用接入 MCP、内部 API、数据库和业务服务后，可用 Tool 很容易增长到几十个甚至上百个。每次把全部 Tool 定义发送给模型会带来以下问题：

1. Tool 名称、描述和参数 Schema 持续占用上下文 Token。
2. 大量用途相近的 Tool 会增加模型误选工具的概率。
3. 当前任务无关的工具定义会形成额外干扰。

`ToolSearchTool` 将工具发现过程拆成两轮：

```text
全部可搜索 Tool
        ↓ 注册到 Provider，不发送给模型
Prompt 常驻 Tool + toolSearch
        ↓ 第一次调用 ChatModel
模型调用 toolSearch(query = "查询城市天气")
        ↓ 搜索并修改绑定的 Prompt
Prompt 常驻 Tool + toolSearch + getWeather
        ↓ 下一次调用 ChatModel
模型调用 getWeather(city = "上海")
```

当应用只有少量 Tool，或者所有 Tool 几乎每轮都会使用时，直接添加到 Prompt 更简单，也能避免一次额外的搜索往返。

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

- `.addTools(...)` 注册的是可搜索 Tool，初始不会发送给模型。
- `.prompt(prompt)` 绑定唯一的 Prompt，并自动把 `toolSearch` 加入可见工具。

### 4. 执行 ChatModel 工具调用循环

```java
AiMessageResponse response = chatModel.chat(prompt);

for (int i = 0; i < 10 && response.hasToolCalls(); i++) {
    prompt.addMessage(response.getMessage());
    prompt.addMessages(response.executeToolCallsAndGetToolMessages());
    response = chatModel.chat(prompt);
}

String answer = response.getMessage().getContent();
```

`response.executeToolCallsAndGetToolMessages()` 执行 `toolSearch` 时，会把命中的 Tool 加入绑定的 Prompt。必须继续调用下一轮 `chatModel.chat(prompt)`，模型才能看到并调用刚刚发现的完整 Tool。搜索只负责发现 Tool，不会代替模型执行目标业务 Tool。

## 常驻 Tool 与可搜索 Tool

### 常驻 Tool

开发者直接添加到 Prompt 的 Tool 始终发送给模型，也不会被自动注册到搜索 Provider：

```java
prompt.addTool(currentTimeTool);
prompt.addTool(askUserTool);
```

常驻 Tool 适合高频基础能力、安全确认或用户交互等必须始终可见的工具。在 `ToolSearchTool` 构建后继续调用 `prompt.addTool(...)` 或主动删除 Tool 也有效；每次搜索、`reset()` 和 `unbind()` 前，绑定逻辑都会重新同步 Prompt 当前的常驻 Tool。

### 可搜索 Tool

只有通过 Builder 注册的 Tool 才进入搜索目录：

```java
ToolSearchTool toolSearch = ToolSearchTool.builder()
    .addTool(weatherTool)
    .addTools(orderTools)
    .prompt(prompt)
    .build();
```

这些 Tool 初始不会出现在 Prompt 中，只有最近一次搜索命中的 Tool 才会在下一轮发送给模型。

## 只保留最近一次搜索结果

`ToolSearchTool` 不累积历次搜索发现的 Tool。每次搜索都会整体替换上一次动态结果：

```text
第一次搜索 weather：Prompt = 常驻 Tool + toolSearch + getWeather
第二次搜索 email：  Prompt = 常驻 Tool + toolSearch + sendEmail
第三次搜索无结果： Prompt = 常驻 Tool + toolSearch
```

这种替换语义可以防止长对话不断积累 Tool，最终重新退化成发送整个工具目录。需要主动清空当前结果时，可以调用：

```java
toolSearch.reset();
```

`reset()` 只移除动态发现的 Tool，不影响常驻 Tool、`toolSearch` 本身和搜索目录。

## 搜索结果为什么只返回名称

模型调用 `toolSearch` 后，Tool Message 返回类似以下内容：

```json
["getWeather", "getWeatherForecast"]
```

名称是已激活 Tool 的稳定引用。下一轮模型请求会携带这些 Tool 的完整名称、描述和参数 Schema，因此无需在 Tool Message 中重复整份定义。模型不是靠名称猜测调用参数，而是在下一轮根据完整定义选择并调用业务 Tool。

如果没有通过 `.prompt(prompt)` 或 `bind(prompt)` 绑定 Prompt，搜索仍会返回名称，但命中 Tool 不会自动出现在后续模型请求中。

## 默认内存搜索

未配置 Provider 时，Builder 自动创建 `InMemoryToolSearchProvider`：

```java
ToolSearchTool toolSearch = ToolSearchTool.builder()
    .addTools(applicationTools)
    .prompt(prompt)
    .build();
```

内存 Provider 使用 `ConcurrentHashMap` 保存 `ToolInfo`，并对以下字段执行加权词法匹配：

- Tool 名称
- Tool 描述
- 参数名称和参数描述
- Category
- Tags

名称匹配权重最高，分类和标签用于补充业务关键词，参数也会递归参与搜索。结果按分数降序排列，默认最多返回 5 个；同分结果按名称排序，保证输出稳定。

`toolSearch` 接受以下参数：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `query` | 是 | 描述所需能力的自然语言查询 |
| `maxResults` | 否 | 最大返回数量，默认 5，必须大于 0 |
| `category` | 否 | 规范化后的 Tool 分类精确过滤 |

默认实现是词法检索，不理解语义同义词。查询与 Tool 描述使用完全不同的表达时，可以优化名称、描述和标签，或者替换 Provider。

## 补充 Tool 元数据

`ToolInfo.from(tool)` 会复制 Tool 的名称、描述和参数，但不会保存执行函数。可以补充 Category、Tags 和自定义 Metadata：

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

`ToolInfo` 只负责检索信息，真正可执行的 Tool 始终由 `ToolSearchManager` 保存在当前进程中。

## 自定义 ToolSearchProvider

当工具目录达到更大规模、需要语义召回或元数据必须集中管理时，可以实现 `ToolSearchProvider`：

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

自定义 Provider 只负责元数据保存和检索。Provider 返回的名称必须能在当前 `ToolSearchManager` 中解析到同名的本地可执行 Tool；只有远程元数据、没有本地执行对象的结果会被忽略。

## Manager、Prompt 与隔离

默认情况下，每次 Builder 构建都会创建独立的 `ToolSearchManager` 和 Provider，因此不同实例的目录及搜索结果互不影响：

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

一个 `ToolSearchTool` 同一时间只能绑定一个 Prompt。需要复用到另一个 Prompt 时，应先解除绑定：

```java
toolSearch.unbind();
toolSearch.bind(anotherPrompt);
```

`unbind()` 会从原 Prompt 移除搜索入口和动态发现结果，并保留开发者当前维护的常驻 Tool。

确实需要让多个实例共享工具目录时，可以显式复用 Manager：

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

`.manager(...)` 和 `.provider(...)` 不能同时配置，因为 Manager 已经持有自己的 Provider。

## ToolSearchTool 与 ToolGroup 的区别

两者都能减少发送给模型的 Tool 数量，但决策者、执行时机和适用问题不同。

| 对比项 | ToolGroup | ToolSearchTool |
| --- | --- | --- |
| 决策者 | 应用代码中的 `ToolGroupMatcher` | 大模型调用 `toolSearch` 描述所需能力 |
| 决策时机 | 第一次模型调用之前 | 搜索 Tool 执行后，下一轮再暴露目标 Tool |
| 匹配依据 | 用户输入、账号、租户、模型及 Context 属性等确定性规则 | 模型生成的 query 与 Tool 元数据匹配 |
| 模型往返 | 不增加 | 通常增加一次搜索 Tool Call |
| Tool 粒度 | 预先划分的一组 Tool | 从统一目录返回最近一次 Top-K Tool |
| 系统提示词 | 命中组可以追加 `systemPrompt` | 不追加业务系统提示词 |
| Prompt 处理 | 为当前请求创建解析结果，不修改原 Prompt | 修改绑定的 Prompt，为下一轮加入搜索结果 |
| 状态 | 每个请求重新运行 Matcher | 保存最近一次发现结果，并在下一次搜索时替换 |

优先使用 ToolGroup 的场景：

- 可以用稳定规则判断工具是否应该出现，例如角色、租户、模型能力或明确关键词。
- 工具天然属于少量固定业务域。
- 必须在第一次模型请求中提供目标 Tool，不希望增加搜索往返。
- 激活 Tool 时还需要追加系统提示词或实施确定性的权限控制。

优先使用 ToolSearchTool 的场景：

- Tool 有几十到几百个，难以维护完整的路由规则。
- Tool 来自多个 MCP Server 或插件，目录会动态增长。
- 用户意图具有长尾表达，由模型描述所需能力更合适。
- 单次任务只使用少量 Tool，并且可以接受一次额外搜索往返。

两者也可以组合：Prompt 直接添加高频基础 Tool，ToolGroup 负责权限、租户和明确业务规则，ToolSearchTool 管理剩余的长尾工具目录。权限校验不应只依赖搜索描述或标签，仍应由 ToolGroup、业务代码或执行拦截器强制实施。

详细用法参见 [ToolSearchTool 中文文档](../docs/zh/chat/tool-search.md) 和 [ToolGroup 中文文档](../docs/zh/chat/tool-group.md)。

## 最佳实践

1. 高频基础 Tool 直接加入 Prompt，低频和长尾 Tool 放入搜索目录。
2. Tool 名称和描述应准确说明动作、对象和边界，避免含糊的名称。
3. 参数描述也参与默认搜索，应写清业务含义，而不只是重复参数名。
4. 为有明确业务分类的 Tool 设置 Category 和 Tags，提高召回稳定性。
5. 控制 `maxResults`，通常返回 3 到 5 个候选即可。
6. 使用 `MemoryPrompt` 保存搜索、Tool Message 和后续业务 Tool Call 的完整消息链。
7. 权限和租户隔离必须在注册或执行 Tool 时强制校验，不依赖模型遵守描述。

## 常见问题

### 搜索到新 Tool 后，旧 Tool 为什么不见了？

这是预期行为。`ToolSearchTool` 只保留最近一次搜索结果，以免长对话持续累积工具定义。常驻 Tool 不受影响。

### Prompt 中直接添加的 Tool 为什么没有进入搜索结果？

Prompt 直接添加的是常驻 Tool，它已经始终对模型可见，无需再次进入搜索目录。只有 Builder 的 `.addTool(...)` 和 `.addTools(...)` 注册的 Tool 可被搜索。

### 自定义 Provider 返回了结果，Prompt 中为什么没有对应 Tool？

Provider 只保存元数据。请确认同名可执行 Tool 已注册到当前 Manager；无法解析到本地执行对象的远程或陈旧结果会被忽略。

### 一个 ToolSearchTool 可以绑定多个 Prompt 吗？

不可以。一个实例只能绑定一个 Prompt。每个对话分别构建实例，或者先调用 `unbind()` 再绑定另一个 Prompt。

### 少量 Tool 需要使用 ToolSearchTool 吗？

通常不需要。少量 Tool 直接发送给模型更简单，也不会增加搜索往返。ToolSearchTool 的主要价值是减少中大型工具目录的上下文成本和选择噪声。

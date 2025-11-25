# ReAct Agent 智能体
<div v-pre>

## 一、ReAct Agent 是什么？

### 1.1 概述

**ReAct Agent**（Reasoning + Acting Agent）是一种结合**语言模型推理能力**与**外部工具调用能力**的智能体架构。它源于“ReAct: Synergizing Reasoning and Acting in Language Models”这一经典范式，旨在让大模型不仅能“思考”，还能“行动”。

在 Agents-Flex 框架中，`ReActAgent` 是该范式的工业级实现，具备以下核心能力：

- ✅ **智能判断**：对于简单问题（如常识问答），直接回答，不进入复杂流程；
- ✅ **工具协同**：对需要外部信息的问题（如查天气、算账、查数据库），自动规划并调用工具；
- ✅ **人机协同**：当缺少必要上下文且无法通过工具获取时，主动请求用户澄清；
- ✅ **状态可恢复**：完整记录执行状态，支持序列化、持久化与中断后恢复；
- ✅ **流式/同步双模式**：适配实时对话与后台任务场景；
- ✅ **高度可扩展**：通过监听器、拦截器、自定义解析器等机制，灵活适配业务需求。

### 1.2 适用场景

- 智能客服（自动查单、预约、答疑）
- 数据分析助手（调用 SQL、API 获取数据后生成报告）
- 个人助理（日程管理、天气提醒、旅行规划）
- 中台智能体编排（作为子 Agent 被其他 Agent 调用）

> 💡 与传统“单轮问答”不同，ReAct Agent 支持**多轮推理 + 工具调用 + 用户交互**的闭环任务执行。


## 二、快速开始：Hello World

### 2.1 引入依赖（Maven）

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-core</artifactId>
    <version>2.0.0</version>
</dependency>
```

### 2.2 最简示例：查询天气

```java
// 1. 定义一个工具
Tool getWeather = Tool.builder()
    .name("getWeather")
    .description("查询指定城市某天的天气")
    .addParameter(Parameter.builder()
        .name("city")
        .type("string")
        .description("城市名称")
        .required(true)
        .build())
    .function(args -> {
        String city = (String)args.get("city");
        return city + " 晴，22°C"; // 简化实现
    })
    .build();

// 2. 创建 ReAct Agent
ChatModel chatModel = new YourChatModel(); // 替换为实际模型客户端
ReActAgent agent = new ReActAgent(chatModel, List.of(getWeather), "北京明天天气如何？");

// 3. 监听最终结果
agent.addListener(new ReActAgentListener() {
    @Override
    public void onFinalAnswer(String answer) {
        System.out.println("🤖 回答: " + answer);
    }
});

// 4. 执行！
agent.execute();
```

输出示例：
```
🤖 回答: 北京明天晴，22°C。
```

### 2.3 交互式示例：缺少信息时请求用户输入

```java
ReActAgent agent = new ReActAgent(chatModel, List.of(getWeather), "明天天气怎么样？");

agent.addListener(new ReActAgentListener() {
    @Override
    public void onRequestUserInput(String question) {
        System.out.println("❓ " + question); // 输出: 请问您想查询哪个城市的天气？
        // 此时执行已暂停，等待用户回复
    }
});

agent.execute();
```

> 后续可通过 **状态恢复机制** 继续执行（见第四部分）。


## 三、基础配置与使用方式

### 3.1 构造方式

| 构造函数 | 说明 |
|--------|------|
| `ReActAgent(chatModel, tools, userQuery)` | 简单任务，从零开始 |
| `ReActAgent(chatModel, tools, userQuery, memoryPrompt)` | 自定义对话记忆策略 |
| `ReActAgent(chatModel, tools, ReActAgentState)` | **从历史状态恢复执行（支持中断续跑）** |

### 3.2 关键配置项

```java
ReActAgentState state = new ReActAgentState();
state.setUserQuery("复杂任务");
state.setMaxIterations(8);           // 默认 20，建议生产环境设为 5~10
state.setStreamable(true);           // 启用流式响应
state.setPromptTemplate(customPrompt); // 自定义提示词

ReActAgent agent = new ReActAgent(chatModel, tools, state);
```

### 3.3 提示词模板（Prompt Template）

默认模板已优化 LLM 行为，强调：
- 优先直接回答
- 严格 ReAct 格式
- 支持 `Request:` 澄清

你也可自定义模板，替换占位符 `{tools}` 和 `{user_input}`：

```java
String tpl = """
你是一个专业助手。请按以下格式响应：
Thought: ...
Action: [工具名]
Action Input: {"param": "value"}
可用工具：
{tools}
用户问题：{user_input}
""";
```

---

## 四、核心机制详解

### 4.1 执行流程

Agent 在每次迭代中：
1. 向 LLM 发送当前对话历史（含工具结果）
2. 解析 LLM 响应类型：
    - **Final Answer** → 返回结果，结束
    - **Request** → 暂停，等待用户输入
    - **ReAct Action** → 执行工具 → 添加 Observation → 继续
3. 重复直至终止（最多 `maxIterations` 次）

### 4.2 状态管理与恢复执行

所有上下文封装在 `ReActAgentState` 中，支持 JSON 序列化：

```java
// 保存状态（如存入数据库）
String json = agent.getState().toJSON();

// 用户回复后恢复
ReActAgentState restored = ReActAgentState.fromJSON(json);
restored.addMessage(new UserMessage("北京")); // 注入用户输入

ReActAgent resumed = new ReActAgent(chatModel, tools, restored);
resumed.addListener(listener);
resumed.execute(); // 从中断处继续
```

> ✅ 此机制是实现**多轮对话、异步任务、会话持久化**的基础。

---

## 五、扩展点与自定义能力

| 扩展点 | 接口/类 | 用途 |
|-------|--------|------|
| **监听事件** | `ReActAgentListener` | 监控工具调用、最终答案、用户请求等 |
| **拦截工具** | `ToolInterceptor` | 权限校验、日志、上下文传递 |
| **解析输出** | `ReActStepParser` | 适配不同 LLM 输出格式 |
| **构建消息** | `ReActMessageBuilder` | 自定义 Observation/错误提示文本 |

### 示例：添加工具调用日志

```java
agent.addInterceptor((context, chain) -> {
    log.info("🔧 调用工具: {}", context.getToolCall().getName());
    return chain.proceed(context);
});
```

### 示例：自定义解析器（适配 XML 输出）

```java
ReActStepParser xmlParser = content -> {
    // 解析 <thought>...</thought><action>...</action> 等
    return steps;
};
agent.setReActStepParser(xmlParser);
```


## 六、协议规范：LLM 输出要求

Agent 依赖 LLM 每次输出**仅包含以下一种类型**：

| 类型 | 标记 | 示例 |
|------|-----|------|
| 直接回答 | 无 | `北京今天20度。` |
| ReAct 步骤 | 含 `Action:` + `Action Input:` | `Thought: ...\nAction: getWeather\nAction Input: {"city":"北京"}` |
| 最终答案 | 含 `Final Answer:` | `Final Answer: 明天晴。` |
| 用户请求 | 含 `Request:` | `Request: 请问是哪个城市？` |

> ❌ 混合输出将导致解析失败，触发 `onStepParseError`。



## 七、总结

`ReActAgent` 不仅是一个工具调用框架，更是一个**支持人机协同、状态可追溯、行为可扩展**的智能体执行引擎。无论你是构建简单问答机器人，还是复杂的多 Agent 协同中台，它都能提供坚实基础。





</div>

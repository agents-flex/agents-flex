<div v-pre>

# ChatModel

## 概述

`ChatModel` 是 Agents-Flex 对大语言模型对话能力的统一接口。OpenAI、DeepSeek、Qwen 等模型实现都可以通过
同一组方法接收 `Prompt` 和 `ChatOptions`，并返回 `AiMessageResponse`。

它解决的是“如何调用模型”，不负责长期保存历史、执行 Tool 或解析业务 JSON：

```text
Prompt + ChatOptions
        ↓
ChatModel
        ↓ 配置、拦截器、HTTP Client
模型服务
        ↓
AiMessageResponse 或流式回调
```

## 适用场景

- 简单问答、摘要和分类：使用 `chat(String)` 直接取得文本；
- 多轮消息、多模态或 Tool Calling：使用 `chat(Prompt, ChatOptions)` 取得完整响应；
- 聊天 UI 和长文本生成：使用 `chatStream(...)` 逐片处理；
- 同一业务切换模型提供商：业务依赖 `ChatModel`，配置层选择具体实现；
- 需要鉴权、缓存、审计或路由：通过 ChatInterceptor 在调用链外层扩展。

## 快速开始

### 添加模型依赖

下面使用 OpenAI 兼容实现，它已经传递依赖核心模块：

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-chat-openai</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

### 创建 ChatModel

```java
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.model.chat.openai.OpenAIChatConfig;

ChatModel chatModel = OpenAIChatConfig.builder()
    .apiKey(System.getenv("AI_API_KEY"))
    .model("gpt-4o-mini")
    .buildModel();
```

`buildModel()` 会先校验 API Key，再创建 `OpenAIChatModel`。生产代码不要把密钥写入源码。

### 同步调用

```java
String answer = chatModel.chat("请用三句话解释什么是向量检索");
System.out.println(answer);
```

这个便捷方法内部会创建 `SimplePrompt`。响应报错时抛出 `ModelException`；如果普通内容为空但模型返回了
`reasoningContent`，方法会回退返回推理内容。

需要 Usage、Tool Call、推理字段等完整信息时，使用 `Prompt` 重载：

```java
SimplePrompt prompt = new SimplePrompt("分析这段日志的根因");
AiMessageResponse response = chatModel.chat(prompt, new ChatOptions());

if (response.isError()) {
    throw new IllegalStateException(response.getErrorMessage());
}
AiMessage message = response.getMessage();
```

## 流式调用

```java
chatModel.chatStream("写一段产品介绍", new StreamResponseListener() {
    @Override
    public void onOpen(StreamContext context) {
        System.out.println("开始生成");
    }

    @Override
    public void onMessage(StreamContext context, AiMessageResponse response) {
        String delta = response.getMessage().getContent();
        if (delta != null) {
            System.out.print(delta);
        }
    }

    @Override
    public void onError(StreamContext context, Throwable error) {
        System.err.println(error.getMessage());
    }

    @Override
    public void onClose(StreamContext context) {
        System.out.println("\n流已关闭");
    }
});
```

`onMessage()` 可能调用多次，其中 `message.content` 是当前片段；需要截至当前的累计文本时读取
`message.fullContent`。`onError()` 只在异常时调用；流一旦打开，无论正常结束还是异常结束，
`onClose()` 都会且只会调用一次，因此公共资源收尾应放在 `onClose()` 中。

## 核心 API

| 方法 | 适用场景 | 返回值 |
| --- | --- | --- |
| `chat(String)` | 最简单的单轮文本 | `String` |
| `chat(String, ChatOptions)` | 单轮文本并覆盖生成参数 | `String` |
| `chat(Prompt)` | 消息、图片或 Tool Calling | `AiMessageResponse` |
| `chat(Prompt, ChatOptions)` | 完整同步调用 | `AiMessageResponse` |
| `chatStream(String, listener)` | 简单文本流式输出 | 回调 |
| `chatStream(Prompt, listener, options)` | 完整流式调用 | 回调 |

`ChatModel` 接口本身只有两个需要实现的核心方法：同步的 `chat(Prompt, ChatOptions)` 和流式的
`chatStream(Prompt, StreamResponseListener, ChatOptions)`；其他重载都是默认便捷方法。

## ChatOptions

`ChatOptions` 控制单次请求，优先于模型配置中的默认值：

```java
ChatOptions options = ChatOptions.builder()
    .model("gpt-4o-mini")
    .temperature(0.2f)
    .maxTokens(800)
    .thinkingEnabled(false)
    .includeUsage(true)
    .addExtraBody("response_format", Map.of("type", "json_object"))
    .build();

String json = chatModel.chat("返回 JSON 格式的产品摘要", options);
```

| 参数 | 作用 | 注意事项 |
| --- | --- | --- |
| `model` | 覆盖默认模型 | 必须是服务端可用名称 |
| `temperature` | 控制随机性 | 代码校验必须大于等于 0 |
| `topP` | 核采样阈值 | 代码校验范围为 0 到 1 |
| `topK` | 候选词数量 | 并非所有厂商支持 |
| `maxTokens` | 最大输出 Token | 不包含输入 Token |
| `stop` | 停止序列 | 由厂商决定支持程度 |
| `thinkingEnabled` | 本次是否启用思考模式 | 模型必须支持 |
| `includeUsage` | 流式响应是否请求 Usage | 厂商可能忽略 |
| `responseFormat` | 结构化输出声明 | 需匹配模型协议 |
| `extraBody` | 透传厂商专有参数 | 使用 `addExtraBody()` |
| `retryEnabled/count/delay` | 覆盖模型级重试 | 只重试框架判定可重试的错误 |

`streaming` 由框架根据调用 `chat()` 还是 `chatStream()` 自动设置，不应由业务代码修改。`contextBotId`、
`contextConversationId`、`contextAccountId`、`contextTurnId` 和 `contextAttributes` 只用于调用链上下文，不会作为
普通模型参数发送。

ChatModel 每次调用都会通过 `ChatOptions.copy()` 创建请求级副本。框架设置的 streaming、拦截器修改的参数和
上下文属性不会回写调用方持有的 Options，因此同一个配置模板可以被并发请求复用。基础复制会保留自定义
ChatOptions 的运行时类型和扩展字段；扩展字段包含可变集合且需要深度隔离时，子类可以覆盖 `copy()`，并调用
`copyBasePropertiesTo(...)` 后再复制自己的扩展字段。

## 如何选择调用方式

| 需求 | 推荐方式 |
| --- | --- |
| 只要最终文本 | `chat(String)` |
| 需要系统消息、历史或图片 | `chat(Prompt)` |
| 需要 Tool Call 或 Usage | 读取 `AiMessageResponse` |
| 页面实时显示 | `chatStream(...)` |
| 需要跨请求历史 | `MemoryPrompt` + `ChatMemory` |

## 生产建议

- 为网络错误、限流和服务端异常设置有限重试，不要重试确定性的 4xx 参数错误；
- 流式回调不要执行耗时阻塞操作，可把片段转交给消息队列或 UI 线程；
- 记录 Provider、模型、耗时和 Usage，但不要默认记录敏感 Prompt；
- Tool Calling 必须处理“模型请求 Tool → 应用执行 → ToolMessage 回传 → 再次调用模型”的循环；
- 为长对话设置上下文裁剪或 Memory 策略，`ChatModel` 不会自动保存历史。

## 常见问题

### chat(String) 为什么返回 null？

当响应、消息、普通内容和推理内容都为空时会返回 `null`。需要区分具体原因时使用返回
`AiMessageResponse` 的重载并检查错误和消息字段。

### temperature 的默认值是多少？

`new ChatOptions()` 不会主动填入温度；最终默认值由具体模型实现或服务端决定。不要把注释中的建议值当作所有
厂商的实际默认值。

### 流式内容应该读取 content 还是 fullContent？

增量推送到前端时读取 `content`；需要覆盖式显示当前完整文本时读取 `fullContent`。

### 可以复用 ChatModel 吗？

通常应把配置完成的模型作为长生命周期对象复用。不要在请求处理中修改共享配置；每次调用的差异放到
`ChatOptions` 和 `Prompt`。

## 下一步

- [配置 ChatModel](./chat-config)
- [构建 Prompt](./prompt)
- [处理 Message](./message)
- [使用 Memory](./memory)
- [扩展对话拦截器](./chat-interceptor)
- [配置错误重试](./retry)

</div>

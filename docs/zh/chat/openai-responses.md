---
title: OpenAI Responses
description: 使用 Responses API 进行文本对话、流式输出和本地工具调用。
---

# OpenAI Responses

`agents-flex-chat-openai` 提供独立的 `OpenAIResponsesChatModel`，使用 `/v1/responses`。
现有 `OpenAIChatModel` 继续使用 Chat Completions；两者共享 `ChatModel` 接口。

## 创建模型

```java
import com.agentsflex.core.model.chat.ChatModel;
import com.agentsflex.model.chat.openai.responses.OpenAIResponsesChatConfig;

OpenAIResponsesChatConfig config = new OpenAIResponsesChatConfig();
config.setApiKey(System.getenv("OPENAI_API_KEY"));
config.setModel("gpt-4.1");
ChatModel model = config.toChatModel();
String answer = model.chat("用一句话解释依赖注入。");
```

可以通过 `setEndpoint`、`setRequestPath` 接入实现 Responses 协议的服务。
无需添加新的依赖。支持实例级拦截器的构造方法为
`new OpenAIResponsesChatModel(config, interceptors)`。

## 参数与输出

- `ChatOptions.maxTokens` 映射为 `max_output_tokens`。
- `responseFormatToJsonObject()` 和 `responseFormatToJsonSchema(...)` 映射为 `text.format`。
- 模型特有参数（如 `reasoning`）通过 `extraBody` 传递；是否支持温度等参数取决于所选模型。
- 使用相同的 `chatStream(...)` 和 `StreamResponseListener` 接收正文或推理摘要增量。
- 最后一条消息的 `finished` 为 `true`，完整正文从 `getFullContent()` 读取。
- 输入、输出 Token 及其明细映射为 `AiMessage` 的对应统计字段。
- `incomplete` 响应保留已有正文；达到输出上限时 `finishReason` 为 `length`，不暴露未完成的工具调用。
- 服务端失败和未收到终止事件的断流进入错误路径；用户主动停止只关闭流，不伪造成功响应。

## 本地工具和历史消息

工具仍由 Agents-Flex 执行。请求构建器把函数定义转换成 Responses 格式，显式使用 `strict: false`
保留现有可选参数的语义；`ToolMessage.toolCallId` 映射为 `function_call_output.call_id`。
流式工具调用在终止响应中一次性提供完整参数，避免把交错的参数分片混合或提前执行。

该适配器默认发送 `store: false`，由调用方通过 `Prompt` / `MemoryPrompt` 提供历史。
同时请求 `reasoning.encrypted_content`，把推理项存入 `AiMessage` 的
`openai.responses.reasoning_items` 元数据，并在下一次请求中回传。
使用推理模型时，请保留返回的消息及其元数据（包括自定义历史存储），不要只保存正文或推理摘要。
框架的 `AiMessage.copy()` 会保留该元数据。

## 当前范围

支持文本输入、同步与 SSE 对话、JSON 输出、本地函数工具及本地历史回传。
图片/音频/视频/文件输入、服务端会话（`previous_response_id` / `conversation`）、后台任务及托管工具
不在此适配器的支持范围内。媒体输入与服务端会话参数会被明确拒绝；不要用 `extraBody` 绕过这些限制。
`stop` 序列不属于 Responses 请求参数，会在构建请求时被拒绝。
Responses 的专有能力和 Chat Completions 参数不能直接互换。

验证使用离线协议样例和模拟传输，不需要 API Key；真实服务仍需使用自己的凭证进行联调。

协议参考：[迁移指南](https://developers.openai.com/api/docs/guides/migrate-to-responses)、
[流式事件](https://platform.openai.com/docs/api-reference/responses-streaming)。

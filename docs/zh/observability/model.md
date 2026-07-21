<div v-pre>

# 模型与 HTTP 可观测

## 什么时候阅读这一页

当你已经完成[快速开始](./getting-started)，并希望知道下面这些问题的数据来源时，阅读本页：

- 模型总耗时和底层 HTTP 耗时为什么不同？
- 流式请求什么时候才算结束？
- Token、会话 ID 和账号 ID 保存在哪里？
- 哪些字段适合做 Dashboard 分组，哪些字段只能用于单次 Trace 查询？

## 自动拦截位置

`BaseChatModel` 在构建责任链时把 `ChatObservabilityInterceptor` 放在最外层，后面依次是全局 Chat
interceptor、实例 interceptor 和实际模型客户端。因此一次标准模型调用会形成：

```text
上游业务 Span（可选）
└── provider.chat / provider.chatStream
    ├── 用户 ChatInterceptor
    └── http.client.request
        └── 模型服务
```

同步调用在 `chat(...)` 返回或抛出异常时结束模型 Span。流式调用在启动请求的线程中创建 Span，但不会把
线程上下文留到异步阶段；每个回调只在自己的线程中临时恢复 Span，并在 `onStop` 或 `onFailure` 结束。
因此流式耗时覆盖完整响应周期，而不是只统计发起 HTTP 请求所需的时间。

## 开关关系

模型可观测同时受两个开关控制：

```java
config.setObservabilityEnabled(true);
```

```properties
agentsflex.otel.enabled=true
```

| 全局开关 | 模型开关 | Chat Span/Metrics | `AgentsFlexHttpClient` Span/Metrics |
| --- | --- | --- | --- |
| `false` | 任意 | 关闭 | 关闭 |
| `true` | `false` | 关闭 | 仍然开启 |
| `true` | `true` | 开启 | 开启 |

模型开关只控制 Chat interceptor。需要完全关闭框架观测时，应使用全局开关。

## Chat Span

同步 Span 名称为 `{provider}.chat`，流式 Span 名称为 `{provider}.chatStream`。provider 或 model 为空时
使用 `unknown`，避免因配置缺失导致埋点异常。

主要属性：

| 属性 | 条件 | 说明 |
| --- | --- | --- |
| `llm.provider` | 始终 | 模型 Provider |
| `llm.model` | 始终 | 模型名称 |
| `llm.operation` | 始终 | `chat` 或 `chatStream` |
| `gen_ai.provider.name` | 始终 | GenAI Provider 属性 |
| `gen_ai.request.model` | 始终 | GenAI 请求模型属性 |
| `gen_ai.operation.name` | 始终 | 当前为 `chat` |
| `llm.total_tokens` | 有响应消息 | 框架计算的有效总 Token |
| `gen_ai.usage.total_tokens` | 有响应消息 | 同一 Token 数据的 GenAI 属性 |
| `gen_ai.conversation.id` | ChatOptions 已设置 | 会话关联 ID |
| `enduser.id` | ChatOptions 已设置 | 账号或最终用户关联 ID |
| `llm.response` | 开启内容采集且存在响应 | 最多 500 个字符 |

当前 Chat interceptor 不把 Prompt 写入 Span。`agentsflex.otel.capture.content=true` 只会增加模型响应属性；
是否允许响应离开业务系统仍应由应用的数据策略决定。

返回 `null`、返回错误响应或抛出异常都会标记 Span 为 `ERROR`。抛出的异常还会通过
`span.recordException(...)` 记录。

## Chat Metrics

| Metric | 类型 | 单位 | 说明 |
| --- | --- | --- | --- |
| `llm.request.count` | Counter | 次 | 模型请求总数 |
| `llm.request.latency` | Histogram | 秒 | 同步或完整流式请求耗时 |
| `llm.request.error.count` | Counter | 次 | 失败请求数 |

Metrics 属性包括：

- `llm.provider`
- `llm.model`
- `llm.operation`
- `llm.success`，boolean
- `gen_ai.provider.name`
- `gen_ai.request.model`
- `gen_ai.operation.name`

conversation、account 和任意用户 ID 不进入 Metrics，避免时间序列基数随用户数量增长。这些关联信息只
保留在 Span 中。

### 如何结合 Span 和 Metric 判断问题

| 观察结果 | 更可能的方向 | 下一步 |
| --- | --- | --- |
| Chat Span 慢，子 HTTP Span 也慢 | 模型服务或网络慢 | 按 `server.address`、model 比较 HTTP 延迟 |
| Chat Span 慢，子 HTTP Span 正常 | 响应消费、自定义 interceptor 或本地处理慢 | 查看两段 Span 的时间差和应用日志 |
| 只有一条 Trace 慢，P95 正常 | 个别请求或偶发依赖问题 | 深入查看该 Trace 的工具和 HTTP 子节点 |
| P95、P99 持续上涨 | 系统性退化或容量问题 | 对比请求量、错误率、部署版本和 provider |
| error count 上涨且 HTTP 为 429 | 上游限流 | 检查并发、配额、重试和模型服务限制 |

这里的 P95 表示：一段时间内 95% 的请求耗时不超过该值。初学者不必先掌握复杂统计，只需记住平均值容易
掩盖少量特别慢的请求，生产监控通常更关注 P95/P99。

## 业务关联字段

使用 `ChatOptions` 设置会话和账号：

```java
ChatOptions options = new ChatOptions();
options.setContextConversationId("conversation-42");
options.setContextAccountId("account-7");

chatModel.chat(prompt, options);
```

流式调用使用相同配置：

```java
chatModel.chatStream(prompt, listener, options);
```

如果还需要 tenant、workflow、request type 等查询维度，可以注册自定义 Chat interceptor。内置
Observability interceptor 位于外层，所以用户 interceptor 中的 `Span.current()` 已经是当前模型 Span：

```java
public final class TenantTraceInterceptor implements ChatInterceptor {
    @Override
    public AiMessageResponse intercept(
        BaseChatModel<?> model, ChatContext context, SyncChain chain) {

        Span.current().setAttribute("app.tenant.id", resolveTenant(context));
        return chain.proceed(model, context);
    }

    @Override
    public void interceptStream(
        BaseChatModel<?> model,
        ChatContext context,
        StreamResponseListener listener,
        StreamChain chain) {

        Span.current().setAttribute("app.tenant.id", resolveTenant(context));
        chain.proceed(model, context, listener);
    }
}

GlobalChatInterceptors.addInterceptor(new TenantTraceInterceptor());
```

全局 interceptor 只会进入注册后创建的 ChatModel。建议在应用启动阶段完成注册，不要在并发请求期间修改
全局列表。

## HTTP Span

`AgentsFlexHttpClient` 为 GET、POST、PUT、DELETE 和 multipart 请求创建 `CLIENT` Span，名称为
`http.client.request`。在模型调用内部使用时，它会自动成为 Chat Span 的子节点。

主要属性：

| 属性 | 说明 |
| --- | --- |
| `http.request.method` | HTTP 方法 |
| `http.method` | 兼容属性 |
| `url.full` | 已移除 user-info、query 和 fragment 的 URL |
| `http.url` | 同一安全 URL 的兼容属性 |
| `server.address` | host，显式端口存在时包含端口 |
| `http.response.status_code` | HTTP 状态码 |
| `http.status_code` | 兼容属性 |

状态码大于等于 400 时 Span 标记为 `ERROR`。IO 异常会记录异常并继续按现有客户端契约抛给调用方。

`getResponse(...)` 把打开的 OkHttp `Response` 交给调用方，因此 Span 会持续到响应体读完、读取失败或
`Response` 被关闭。调用方必须使用 try-with-resources；如果不关闭响应，HTTP Span 和连接都可能长时间
占用。

```java
try (Response response = client.getResponse(url, headers)) {
    String body = response.body() == null ? null : response.body().string();
}
```

## HTTP Metrics

| Metric | 类型 | 单位 | 说明 |
| --- | --- | --- | --- |
| `http.client.request.count` | Counter | 次 | HTTP 请求总数 |
| `http.client.request.duration` | Histogram | 秒 | 请求耗时；开放响应包含响应体消费时间 |
| `http.client.request.error.count` | Counter | 次 | 异常或状态码大于等于 400 的请求数 |

HTTP Metrics 属性为 `http.method`、`server.address`、`http.success`，收到响应后还包含
`http.response.status_code`。完整 URL 不进入 Metrics，避免路径参数和查询参数造成高基数。

## Trace Context 传播

HTTP 请求使用当前 OpenTelemetry 的 propagator 向请求头注入 Trace Context。Agents-Flex 自建 SDK 默认
使用 W3C Trace Context；复用应用 SDK 时使用应用配置的 propagator。

如果自定义 interceptor 把任务切换到其他线程或线程池，应用仍需按照 OpenTelemetry 规则显式传播
`Context`。框架只保证自身流式回调和 HTTP 客户端的上下文边界，不会自动修复任意用户异步代码。

## 一个完整示例

用户发起一次流式模型调用，模型 HTTP 在 1 秒后返回首批数据，10 秒后流式结束：

```text
openai.chatStream             10.0s
└── http.client.request        1.0s 或持续到响应体关闭
```

此时 `llm.request.latency` 记录完整 10 秒，因为用户实际等待到流结束；HTTP Span 的结束时间取决于客户端如何
消费响应体。分析时不要把两者都简单理解为“模型服务器计算时间”。

## 相关文档

- [快速开始](./getting-started)
- [典型场景与实践](./scenarios)
- [工具调用可观测](./tool)
- [JDBC 持久化](./jdbc)
- [故障排查与生产建议](./troubleshooting)

</div>

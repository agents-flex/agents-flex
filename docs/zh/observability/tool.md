<div v-pre>

# 工具调用可观测

## 什么时候阅读这一页

当系统使用搜索、天气、数据库、文件处理等 Tool，并需要回答“调用了哪个工具、花了多久、为什么失败”时，
阅读本页。完全不使用 `ToolExecutor` 的项目可以跳过。

## 自动接入 ToolExecutor

每个 `ToolExecutor` 默认在责任链最外层加入一个 `ToolObservabilityInterceptor`：

```text
ToolObservabilityInterceptor
└── 全局 ToolInterceptor
    └── 当前 ToolExecutor 的实例 interceptor
        └── Tool.invoke(args)
```

用户不需要手动执行：

```java
GlobalToolInterceptors.addInterceptor(new ToolObservabilityInterceptor());
```

如果全局或实例 interceptor 列表中已经存在该类型，`ToolExecutor` 不会再次自动添加，避免同一次调用产生
两个工具 Span。正常项目应保留自动注册，只把权限、审计和业务属性等逻辑放入自定义 interceptor。

## Tool Span

每次执行创建一个名为 `tool.{toolName}` 的 Span。因为模型调用期间工具通常由应用在模型响应后执行，工具
Span 是否属于模型 Span，取决于执行工具时是否仍有有效的 OpenTelemetry Context；不要仅依赖线程局部
变量推断跨异步阶段的父子关系。

主要属性：

| 属性 | 条件 | 说明 |
| --- | --- | --- |
| `gen_ai.tool.name` | 始终 | GenAI 工具名称属性 |
| `agentsflex.gen_ai.tool.arguments` | 开启内容采集 | 脱敏并限制长度的 JSON 参数 |
| `agentsflex.gen_ai.tool.result` | 开启内容采集且结果非空 | 脱敏并限制长度的结果 |

工具名称采用 OpenTelemetry GenAI 标准属性。参数和结果属于可选的 Agents-Flex 内容扩展，因此使用
`agentsflex.*` 命名空间；旧的 `tool.*` 属性不再写入。

工具抛出 `Exception`、`RuntimeException` 或 `Error` 时，Span 会标记为 `ERROR` 并记录异常。Metric 的
`error.type` 保存实际异常类名，例如 `java.net.SocketTimeoutException`，而不是根据异常继承关系猜测
“业务异常”或“系统异常”。

## Tool Metrics

| Metric | 类型 | 单位 | 说明 |
| --- | --- | --- | --- |
| `agentsflex.gen_ai.tool.call.count` | Counter | 次 | 工具调用总数 |
| `agentsflex.gen_ai.tool.call.duration` | Histogram | 秒 | 完整工具执行耗时 |
| `agentsflex.gen_ai.tool.call.error.count` | Counter | 次 | 失败调用数 |

Metrics 属性：

- `gen_ai.tool.name`
- `error.type`，仅失败时存在

不要把参数、账号、conversation ID 或 request ID 加入 Tool Metrics。它们通常具有高基数，应保留在 Span
或业务审计记录中。

## 排除特定工具

心跳、健康检查或高频内部工具可能不需要单独观测，可以通过系统属性排除：

```bash
-Dagentsflex.otel.tool.excluded=heartbeat,debug_cache
```

工具名使用逗号分隔，配置项会去除首尾空格，匹配区分大小写。排除后不会创建 Span，也不会记录 Metrics，
但工具仍然正常执行。

全局关闭：

```bash
-Dagentsflex.otel.enabled=false
```

排除配置和全局开关在每次工具调用时读取，新值不要求重建 `ToolExecutor`。

## 内容采集与脱敏

默认配置：

```properties
agentsflex.otel.capture.content=false
```

默认只记录工具名、耗时和状态。开启后：

```bash
-Dagentsflex.otel.capture.content=true
```

参数处理流程：

```text
ToolCall.arguments
        │ JSON.parse
        ├── 解析失败 ──> [UNPARSEABLE_CONTENT_REDACTED]
        └── 解析成功
              │ 递归遍历 object / array
              │ 敏感 key 的 value 替换为 ***
              └── JSON 序列化并限制为 4000 字符
```

敏感 key 匹配不区分大小写，覆盖 password、passwd、token、secret、apiKey、authorization、auth、
credential、cookie、session 和 cert 等常见名称。嵌套 object 和 array 会递归处理。

结果处理规则：

| 结果类型 | Span 中的值 |
| --- | --- |
| `byte[]` | `[binary_data]` |
| `InputStream` 或 `File` | `[stream_or_file]` |
| 普通对象 | JSON 序列化、递归脱敏、最多 4000 字符 |
| 序列化失败 | `[SERIALIZATION_ERROR]` |

脱敏只处理结构化字段名，无法判断普通字符串中是否包含姓名、手机号、业务正文或其他敏感信息。内容采集
应该保持 opt-in，并由应用根据工具用途决定是否允许。

## 添加业务属性

内置可观测 interceptor 位于外层，因此用户 Tool interceptor 中可以给当前 Span 添加业务维度：

```java
public final class ToolTenantInterceptor implements ToolInterceptor {
    @Override
    public Object intercept(ToolContext context, ToolChain chain) throws Exception {
        Span.current().setAttribute("app.tenant.id", currentTenantId());
        Span.current().setAttribute("app.tool.category", classify(context.getTool()));
        return chain.proceed(context);
    }
}

GlobalToolInterceptors.addInterceptor(new ToolTenantInterceptor());
```

这类属性进入 Span，不会自动复制到 Metrics。全局 interceptor 应在应用启动时注册，避免运行期间并发修改
全局列表。

## 调用示例

```java
ToolCall call = new ToolCall(
    "call-1",
    "getWeather",
    "{\"city\":\"Hangzhou\",\"apiKey\":\"secret\"}"
);

ToolExecutor executor = new ToolExecutor(getWeatherTool, call);
Object result = executor.execute();
```

开启内容采集时，`agentsflex.gen_ai.tool.arguments` 中的 `apiKey` 会变成 `***`。工具收到的实际参数不会被修改；脱敏只作用
于导出的观测副本。

## 场景：天气工具偶发超时

假设 `getWeather` 内部调用外部天气服务，用户偶尔得到失败响应。Trace 可能显示：

```text
tool.getWeather               10.1s  ERROR
└── http.client.request       10.0s  ERROR
    server.address=weather.example.com
```

结合数据可以逐步判断：

1. Tool Span 与 HTTP Span 都接近 10 秒，说明主要耗时不是参数解析；
2. HTTP Span 状态码为空且有 `SocketTimeoutException`，说明在收到响应前超时；
3. 如果 `agentsflex.gen_ai.tool.call.error.count` 只在该工具上涨，可以排除所有工具共同故障；
4. 如果相同 host 的 HTTP P95 同时上涨，应检查外部服务、网络和超时配置。

如果工具内部没有使用 `AgentsFlexHttpClient` 或其他 OTel HTTP instrumentation，仍会看到 Tool Span 和异常，
但不会自动得到内部 HTTP 子 Span。

## 相关文档

- [Observability 模块概述](./observability)
- [典型场景与实践](./scenarios)
- [模型与 HTTP 可观测](./model)
- [JDBC 持久化](./jdbc)
- [故障排查与生产建议](./troubleshooting)

</div>

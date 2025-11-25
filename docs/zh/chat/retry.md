# 错误重试
<div v-pre>


## 概述

Agents-Flex 内置了**可配置的自动重试机制**，用于提升与大语言模型（LLM）服务交互的**健壮性与容错能力**。当遇到网络抖动、服务限流（429）、临时故障（5xx）等可恢复错误时，系统会自动重试请求，避免因瞬时问题导致任务失败。

该机制具有以下特点：

- **统一入口**：通过 `Retryer.retry()` 工具方法实现
- **灵活配置**：支持按模型/请求级别设置重试次数与延迟
- **指数退避**：自动应用指数退避策略（如 1s → 2s → 4s）
- **透明集成**：对上层业务逻辑完全透明，无需手动处理重试逻辑


## 核心组件

### `Retryer` 工具类

```java
public class Retryer {
    public static <T> T retry(Callable<T> callable, int retryCount, int initialDelayMs) throws Exception;
}
```

- **功能**：执行可重试操作，最多 `retryCount` 次
- **退避策略**：第 `n` 次重试前等待 `initialDelayMs * 2^(n-1)` 毫秒（指数退避）
- **异常传播**：若所有重试均失败，抛出最后一次异常

> 📌 **注意**：所有 **非致命异常**（如 `IOException`、`HttpException`）都会触发重试。


### 重试配置来源

重试参数通过 `ChatRequestSpec` 传递，其值来源于 `ChatOptions` 或者 `ChatConfig`：

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `retryEnabled` | `boolean` | `true` | 是否启用重试（全局开关） |
| `retryCount` | `int` | `3` | 最大重试次数（不包含首次尝试） |
| `retryInitialDelayMs` | `int` | `1000`（1秒） | 首次重试延迟，后续按指数增长 |

> **配置优先级**：`ChatOptions`（运行时） > `ChatConfig`（模型级） > 默认值


## 重试策略详解

### 指数退避（Exponential Backoff）

| 重试次数 | 延迟时间（初始 1000ms） |
|----------|------------------------|
| 第 1 次（初始请求） | 0ms |
| 第 2 次（第 1 次重试） | 1000ms |
| 第 3 次（第 2 次重试） | 2000ms |
| 第 4 次（第 3 次重试） | 4000ms |

> 🔁 总最大等待时间 ≈ `initialDelay * (2^retryCount - 1)`

### 重试 vs 不重试

| 场景 | 是否重试 | 说明 |
|------|--------|------|
| HTTP 4xx（非 429） | ❌ 否 | 如 400（参数错误）、401（认证失败） |
| HTTP 429 | ✅ 是 | 限流，通常可恢复 |
| HTTP 5xx | ✅ 是 | 服务端临时错误 |
| 网络异常 | ✅ 是 | 超时、连接中断等 |
| 业务逻辑错误（如解析失败） | ❌ 否 | 在 `parseResponse` 中处理，不触发重试 |

> ⚠️ 重试仅针对 **网络/传输层异常**，不处理业务语义错误（如 API 返回 `{"error": "invalid_model"}`）。


## 配置示例

### 1 全局禁用重试（模型级）

```java
OpenAIConfig config = new OpenAIConfig();
config.setRetryEnabled(false); // 完全关闭
OpenAIChatModel model = new OpenAIChatModel(config);
```

### 2 调整重试参数

```java
config.setRetryCount(5);               // 最多重试 5 次
config.setRetryInitialDelayMs(500);    // 初始延迟 500ms
```

> 💡 **建议值**：
> - 生产环境：`retryCount = 2~3`
> - 高延迟网络：`retryInitialDelayMs = 2000`




## 最佳实践

### ✅ 推荐做法
- **保持默认配置**：`retryCount=3` 适用于大多数场景
- **关键任务增加重试**：对可靠性要求高的场景可设为 `retryCount=5`
- **监控重试率**：通过日志分析重试频率，优化网络或服务配置

### ❌ 避免事项
- 不要设置过高的 `retryCount`（如 >10），避免雪崩
- 不要禁用重试（除非调试），瞬时故障很常见
- 不要依赖重试解决持续性错误（如错误的 API Key）



## 总结

Agents-Flex 的重试机制：

- **开箱即用**：默认启用，无需额外代码
- **智能退避**：指数延迟避免加重服务负担
- **配置灵活**：支持模型级/请求级调整
- **场景覆盖**：同步/流式请求均支持

> 📌 重试是**提升可用性**的手段，不是**掩盖问题**的工具。持续高重试率表明底层服务或网络存在隐患。








</div>

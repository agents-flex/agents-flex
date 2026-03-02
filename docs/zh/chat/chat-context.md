# 对话上下文 ChatContext


## 概述

`ChatContext` 是 Agents-Flex 框架中用于**在 LLM 调用全链路中传递上下文信息**的核心容器类。它作为线程局部上下文（通过 `ChatContextHolder` 管理），在以下场景中被广泛使用：

- **拦截器（`ChatInterceptor`）** 之间共享数据
- **请求构建阶段** 到 **实际调用阶段** 的参数传递
- **自定义逻辑** 中临时存储或读取元信息

`ChatContext` 本身是**不可变属性的集合**，但其内部对象（如 `attributes`、`requestSpec`）可被修改，从而实现动态行为控制。


## ChatContext 核心属性说明

| 属性 | 类型 | 说明 | 是否可修改 |
|------|------|------|-----------|
| **`prompt`** | `Prompt` | 用户原始输入的对话上下文（含多轮消息、工具定义等） | ✅ 可替换（谨慎） |
| **`config`** | `ChatConfig` | 当前模型的配置（API Key、Endpoint、能力声明等） | ❌ 不建议修改 |
| **`options`** | `ChatOptions` | 本次调用的生成参数（temperature、maxTokens 等） | ✅ 可调整 |
| **`requestSpec`** | `ChatRequestSpec` | **即将发送的协议请求规范**，含 URL、Headers、Body | ✅ 可修改（高级用法） |
| **`attributes`** | `Map<String, Object>` | **开发者自定义属性区**，用于拦截器间传递临时数据 | ✅ 完全可控 |

> 🔐 **安全提示**：
> - `config` 包含敏感信息（如 `apiKey`），**禁止在日志/响应中直接输出**
> - 修改 `requestSpec` 需了解底层协议（如 OpenAI JSON Schema），否则可能导致请求失败


## 核心方法

### 1. 属性访问（Getter/Setter）
所有核心属性均提供标准 getter/setter，例如：
```java
Prompt prompt = context.getPrompt();
ChatConfig config = context.getConfig();
```

### 2. 自定义属性操作
```java
// 添加/更新属性
context.addAttribute("traceId", "abc123");
context.addAttribute("userRole", "admin");

// 批量设置（覆盖原有）
Map<String, Object> attrs = Map.of("tenant", "t1", "region", "us-west");
context.setAttributes(attrs);

// 读取属性（需自行判空和类型转换）
String traceId = (String) context.getAttributes().get("traceId");
```


##  使用场景示例

### 场景 1：拦截器间传递数据
```java
// 拦截器 A：生成并传递 trace ID
public class TraceInterceptor implements ChatInterceptor {
    @Override
    public AiMessageResponse intercept(BaseChatModel<?> model, ChatContext context, SyncChain chain) {
        String traceId = UUID.randomUUID().toString();
        context.addAttribute("traceId", traceId);
        MDC.put("traceId", traceId); // 用于 SLF4J 日志
        return chain.proceed(model, context);
    }
    // ... 流式方法类似
}

// 拦截器 B：读取 trace ID 并上报指标
public class MetricsInterceptor implements ChatInterceptor {
    @Override
    public AiMessageResponse intercept(BaseChatModel<?> model, ChatContext context, SyncChain chain) {
        String traceId = (String) context.getAttributes().get("traceId");
        long start = System.currentTimeMillis();
        try {
            return chain.proceed(model, context);
        } finally {
            long duration = System.currentTimeMillis() - start;
            Metrics.record("llm_latency", duration, "traceId", traceId);
        }
    }
}
```

### 场景 2：动态修改请求头
```java
public class DynamicAuthInterceptor implements ChatInterceptor {
    @Override
    public AiMessageResponse intercept(BaseChatModel<?> model, ChatContext context, SyncChain chain) {
        // 从上下文或外部服务获取 token
        String token = TokenService.getValidToken(context.getConfig().getProvider());

        // 修改请求头
        context.getRequestSpec().getHeaders().put("Authorization", "Bearer " + token);

        return chain.proceed(model, context);
    }
}
```

### 场景 3：条件性禁用某功能
```java
public class SafetyCheckInterceptor implements ChatInterceptor {
    @Override
    public AiMessageResponse intercept(BaseChatModel<?> model, ChatContext context, SyncChain chain) {
        // 若检测到敏感词，强制关闭 thinking 模式
        if (containsSensitiveWords(context.getPrompt())) {
            context.getOptions().setThinkingEnabled(false);
        }
        return chain.proceed(model, context);
    }
}
```


##  注意事项与最佳实践

1. **线程安全**
   `ChatContext` 由 `ChatContextHolder` 通过 `ThreadLocal` 管理，**天然线程隔离**，无需额外同步。

2. **生命周期**
   每次 `chat()` 或 `chatStream()` 调用会创建**新的 `ChatContext`**，调用结束后自动清理（通过 try-with-resources）。

3. **避免存储大对象**
   `attributes` 仅用于传递轻量元数据（ID、标志位、小配置），勿存文件、大字符串等。

4. **不要缓存 `ChatContext` 引用**
   调用结束后上下文即失效，持有引用可能导致内存泄漏或数据错乱。


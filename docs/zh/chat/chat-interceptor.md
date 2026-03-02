# 对话拦截器 ChatInterceptor

## 概述

`ChatInterceptor` 是 Agents-Flex 框架提供的**责任链模式拦截器接口**，允许开发者在 LLM 调用前后插入自定义逻辑，适用于**日志记录、缓存、敏感词处理、认证鉴权、请求/响应修改、限流熔断、指标监控、内容脱敏**等横切关注点。

拦截器支持**同步**与**流式**两种调用模式，并可按作用范围分为：
- **全局拦截器**：通过 `GlobalChatInterceptors` 注册，作用于所有 `ChatModel` 实例
- **实例级拦截器**：在构造 `ChatModel` 时传入，仅作用于当前实例

> ⚠️ **注意**：可观测性（OpenTelemetry）由框架自动注入（当 `config.isObservabilityEnabled() == true`），开发者无需手动添加。

---

##  拦截器执行顺序

每次调用 `chat()` 或 `chatStream()` 时，框架会按以下顺序构建责任链：

```
[可观测性拦截器] → [全局拦截器] → [实例级拦截器] → [实际 LLM 调用]
```

- **最外层（最先执行）**：可观测性拦截器（自动注入）
- **中间层**：全局拦截器，通过 `GlobalChatInterceptors.addInterceptor()` 注册
- **内层（最后执行）**：实例级拦截器（构造时传入）
- **链尾**：执行真正的 HTTP/SSE 调用

> ❗ 所有拦截器均可读取或修改 `ChatContext`，从而影响后续流程。


## 核心接口说明

### 1. `ChatInterceptor` 接口

```java
public interface ChatInterceptor {
    // 同步调用拦截
    AiMessageResponse intercept(BaseChatModel<?> chatModel
        , ChatContext context
        , SyncChain chain);

    // 流式调用拦截
    void interceptStream(BaseChatModel<?> chatModel
        , ChatContext context
        , StreamResponseListener listener
        , StreamChain chain);
}
```

#### 方法参数说明

| 参数 | 说明 |
|------|------|
| `chatModel` | 当前调用的模型实例，可获取配置（`config`）、客户端等 |
| `context` | **线程上下文对象**，包含 `Prompt`、`ChatOptions`、`ChatRequestSpec` 等，**可修改** |
| `listener` | 流式回调监听器（仅流式） |
| `chain` | **责任链的下一个节点**，必须调用 `chain.proceed(...)` 以继续执行 |

> ❗ **关键规则**：
> - 拦截器**必须**调用 `chain.proceed(...)`，否则调用链中断，LLM 不会被调用
> - 可在 `proceed` 前后添加逻辑（如前置检查、后置处理）
> - 可抛出异常中断流程（如同步调用中抛出 `ModelException`）

---

### 2. 责任链接口

- **`SyncChain`**（同步）
  ```java
  AiMessageResponse proceed(BaseChatModel<?> model
  , ChatContext context);
  ```

- **`StreamChain`**（流式）
  ```java
  void proceed(BaseChatModel<?> model
  , ChatContext context
  , StreamResponseListener listener);
  ```

> 💡 `proceed()` 返回/触发的是**链中下一个拦截器**，直到最终执行 LLM 调用。

---

## 使用方式

### 一、创建自定义拦截器

#### 示例 1：记录请求耗时（同步）
```java
public class TimingInterceptor implements ChatInterceptor {
    @Override
    public AiMessageResponse intercept(BaseChatModel<?> model, ChatContext context, SyncChain chain) {
        long start = System.currentTimeMillis();
        try {
            return chain.proceed(model, context); // 继续执行
        } finally {
            long duration = System.currentTimeMillis() - start;
            System.out.println("LLM 调用耗时: " + duration + "ms");
        }
    }

    @Override
    public void interceptStream(BaseChatModel<?> model, ChatContext context,
                                StreamResponseListener listener, StreamChain chain) {
        long start = System.currentTimeMillis();
        chain.proceed(model, context, listener); // 流式不阻塞，仅记录开始时间
        // 如需记录完整流式耗时，需包装 listener
    }
}
```

#### 示例 2：动态注入认证头
```java
public class AuthHeaderInterceptor implements ChatInterceptor {
    @Override
    public AiMessageResponse intercept(BaseChatModel<?> model, ChatContext context, SyncChain chain) {
        // 修改请求头
        Map<String, String> headers = context.getRequestSpec().getHeaders();
        headers.put("Authorization", "Bearer " + getDynamicToken());
        return chain.proceed(model, context);
    }

    @Override
    public void interceptStream(BaseChatModel<?> model, ChatContext context,
                                StreamResponseListener listener, StreamChain chain) {
        Map<String, String> headers = context.getRequestSpec().getHeaders();
        headers.put("Authorization", "Bearer " + getDynamicToken());
        chain.proceed(model, context, listener);
    }

    private String getDynamicToken() {
        // 实现动态 token 获取逻辑
        return "your-token";
    }
}
```


### 二、注册拦截器

#### 1. 全局拦截器（推荐用于通用逻辑）
在应用启动时注册（如 Spring `@PostConstruct`）：
```java
// 注册单个
GlobalChatInterceptors.addInterceptor(new TimingInterceptor());

// 批量注册
GlobalChatInterceptors.addInterceptors(List.of(
    new AuthHeaderInterceptor(),
    new TimingInterceptor()
));
```

> ✅ 全局拦截器对**所有后续创建的 `ChatModel` 实例**生效。

#### 2. 实例级拦截器（用于特定模型）
```java
List<ChatInterceptor> instanceInterceptors = List.of(new CustomValidatorInterceptor());
ChatModel chatModel = new OpenAIChatModel(config, instanceInterceptors);
```

#### 3. 动态添加（运行时）
```java
chatModel.addInterceptor(new DebugInterceptor());
// 或指定位置插入
chatModel.addInterceptor(0, new HighPriorityInterceptor());
```



## 最佳实践

1. **轻量 & 无状态**
   拦截器应尽量轻量，避免阻塞 I/O。状态信息通过 `ChatContext.setAttribute()` 传递。

2. **异常安全**
   在 `try-finally` 中调用 `chain.proceed()`，确保链不被意外中断。

3. **流式监听器包装要完整**
   若包装 `StreamResponseListener`，需代理所有回调方法，避免行为异常。

4. **全局 vs 实例**
    - 全局：日志、监控、统一认证
    - 实例：业务特定逻辑（如某模型需特殊处理）

5. **避免重复逻辑**
   不要同时在全局和实例中注册相同功能的拦截器。

6. **测试覆盖**
   编写单元测试验证拦截器行为，可使用 `GlobalChatInterceptors.clear()` 在测试前后清理。

---

## 常见问题

**Q：拦截器能修改 LLM 的返回结果吗？**

A：可以。同步拦截器可修改 `AiMessageResponse` 后返回；流式需包装 `listener` 并修改 `AiMessage`。或者在某些敏感词检查的情况下，直接创建 `AiMessageResponse` 返回，
而不经过大模型。

**Q：如何获取完整的请求/响应日志？**

A：框架已内置 `ChatMessageLogger`（受 `logEnabled` 控制），无需自定义日志拦截器，除非需特殊格式。

**Q：拦截器执行顺序能调整吗？**

A：全局拦截器按注册顺序执行；实例拦截器按传入列表顺序执行；可观测性始终在最外层。

**Q：流式拦截器中如何知道调用何时结束？**

A：无法直接知道，但可通过包装 `listener`，在 `onStop()` 或 `onFailure()` 中触发结束逻辑。

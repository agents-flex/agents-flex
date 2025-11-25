# Tool Interceptor 工具调用拦截器
<div v-pre>



## 概述

`ToolInterceptor` 是 Agents-Flex 框架中用于**拦截和增强工具调用行为**的核心扩展点。通过责任链模式，开发者可以在工具执行前后插入横切逻辑，实现：

- **可观测性**：追踪、指标、日志
- **安全控制**：权限校验、参数脱敏
- **业务增强**：缓存、重试、审计
- **错误处理**：统一异常包装、降级策略

框架内置了 `ToolObservabilityInterceptor` 作为生产级参考实现，同时支持全局与实例级拦截器注册。


## 核心接口

### 1. `ToolInterceptor` 接口

```java
public interface ToolInterceptor {
    Object intercept(ToolContext context, ToolChain chain) throws Exception;
}
```
- **`context`**：包含工具定义、调用参数、临时属性等上下文信息
- **`chain`**：责任链的下一个节点（必须调用 `chain.proceed()` 以继续执行）

> ⚠️ **注意事项**：
> - 拦截器**必须**调用 `chain.proceed(context)`，否则工具不会被执行
> - 可在 `proceed()` 前后添加逻辑（前置检查、后置处理）
> - 可抛出异常中断流程（如权限拒绝）


### 2. `ToolContext` 执行上下文

`ToolContext` 是贯穿整个拦截链的核心容器：

```java
public class ToolContext implements Serializable {
    public Tool getTool();         // 当前工具定义
    public ToolCall getToolCall(); // LLM 请求的调用详情（含原始参数）
    public Map<String, Object> getArgsMap(); // 已解析的参数 Map
    public void setAttribute(String key, Object value); // 设置临时属性
    public <T> T getAttribute(String key); // 获取临时属性
}
```

> 💡 **典型用法**：
> ```java
> // 拦截器 A：设置 traceId
> context.setAttribute("traceId", UUID.randomUUID().toString());
>
> // 拦截器 B：读取 traceId
> String traceId = context.getAttribute("traceId");
> ```


## 拦截器注册方式

### 1. 全局拦截器（推荐用于通用逻辑）
在应用启动时注册，作用于**所有工具调用**：
```java
// 注册可观测性拦截器
GlobalToolInterceptors.addInterceptor(new ToolObservabilityInterceptor());

// 注册自定义拦截器
GlobalToolInterceptors.addInterceptor(new PermissionInterceptor());
```

### 2. 实例级拦截器（用于特定场景）
在创建 `ToolExecutor` 时传入，仅作用于当前执行：
```java
ToolExecutor executor = new ToolExecutor(tool, toolCall,
    List.of(new SensitiveDataMaskInterceptor())
);
Object result = executor.execute();
```

### 执行顺序
```
[全局拦截器 1] → [全局拦截器 2] → ...
→ [实例拦截器 1] → [实例拦截器 2] → [实际工具调用]
```


## 内置实现：`ToolObservabilityInterceptor`

框架提供的**生产级可观测性拦截器**，自动集成 OpenTelemetry，支持：

### 核心能力
| 能力 | 说明 |
|------|------|
| **自动追踪** | 创建 `tool.{name}` Span，记录参数与结果 |
| **指标上报** | 调用计数、延迟直方图、错误计数 |
| **参数脱敏** | 自动屏蔽密码、token 等敏感字段 |
| **结果安全** | 避免二进制/大对象污染 Span |
| **动态开关** | 支持全局关闭或按工具名排除 |

### 配置方式
- **全局启用**：确保 `Observability.isEnabled() == true`（默认开启）
- **排除特定工具**：`Observability.excludeTool("dangerous_tool")`

### 输出示例（Span Attributes）
```text
tool.name = "getWeather"
tool.arguments = {"city": "Beijing", "apiKey": "***"}
tool.result = {"temperature": 22, "unit": "celsius"}
```

> 📊 **上报指标**：
> - `tool.call.count`：总调用次数
> - `tool.call.latency`：调用延迟（秒）
> - `tool.call.error.count`：错误次数


## 自定义拦截器开发指南

### 示例 1：权限校验拦截器
```java
public class PermissionInterceptor implements ToolInterceptor {
    @Override
    public Object intercept(ToolContext context, ToolChain chain) throws Exception {
        String toolName = context.getTool().getName();
        String userId = context.getAttribute("userId");

        if (!hasPermission(userId, toolName)) {
            throw new SecurityException("Permission denied for tool: " + toolName);
        }

        return chain.proceed(context);
    }

    private boolean hasPermission(String userId, String toolName) {
        // 实现权限逻辑
        return true;
    }
}
```

### 示例 2：参数校验拦截器
```java
public class ValidationInterceptor implements ToolInterceptor {
    @Override
    public Object intercept(ToolContext context, ToolChain chain) throws Exception {
        Map<String, Object> args = context.getArgsMap();
        String toolName = context.getTool().getName();

        // 校验必填参数
        if ("createUser".equals(toolName) && args.get("email") == null) {
            throw new IllegalArgumentException("Email is required");
        }

        return chain.proceed(context);
    }
}
```

### 示例 3：缓存拦截器
```java
public class CachingInterceptor implements ToolInterceptor {
    private final Cache<String, Object> cache = ...;

    @Override
    public Object intercept(ToolContext context, ToolChain chain) throws Exception {
        String cacheKey = buildCacheKey(context);
        Object cached = cache.get(cacheKey);
        if (cached != null) {
            return cached; // 命中缓存，跳过实际调用
        }

        Object result = chain.proceed(context);
        cache.put(cacheKey, result); // 写入缓存
        return result;
    }
}
```


## 上下文管理：`ToolContextHolder`

提供线程安全的上下文访问：
```java
// 在任意位置获取当前工具调用上下文
ToolContext current = ToolContextHolder.currentContext();
if (current != null) {
    String toolName = current.getTool().getName();
}
```

> ⚠️ **注意**：该方式仅在工具调用期间获取有效

## 常见问题

**Q：拦截器能修改工具参数吗？**

A：**不能直接修改** `context.getArgsMap()`（它是 `ToolCall` 的只读视图），但可通过以下方式：
- 在前置拦截器中验证并拒绝非法参数
- 使用 `context.setAttribute()` 传递修正后的参数给后续拦截器或工具实现

**Q：如何跳过实际工具调用？**

A：在拦截器中**不调用** `chain.proceed()`，直接返回结果（如缓存命中场景）。

**Q：拦截器执行顺序能调整吗？**

A：全局拦截器按注册顺序执行；实例拦截器按传入列表顺序执行。

**Q：性能开销大吗？**

A：拦截器开销极低：
- `ToolObservabilityInterceptor` 采用懒序列化
- 敏感字段脱敏使用高效正则
- 可通过开关动态禁用




</div>

# Function Call/ Tool Call 可观测性
<div v-pre>

## 1. 核心概念

在 Agents-Flex 框架中，**工具函数调用（Function Call / Tool Call）可观测性**提供对每次函数调用的 **端到端追踪（Trace）** 和 **指标监控（Metrics）** 能力。

主要概念：

1. **工具执行器（ToolExecutor）**

    * 负责执行单个工具函数调用
    * 支持 **责任链拦截器（Interceptor Chain）**，顺序：

        1. 全局拦截器
        2. 用户自定义拦截器
        3. 实际函数调用
    * 可动态添加拦截器

2. **责任链拦截器（ToolInterceptor）**

    * 定义横切逻辑接口：

      ```java
      Object intercept(ToolContext context, ToolChain chain) throws Exception;
      ```
    * 常用于：

        * 日志记录
        * 权限校验
        * 异常监控
        * 可观测性上报

3. **可观测性拦截器（ToolObservabilityInterceptor）**

    * 实现 `ToolInterceptor`，自动完成：

        * **Trace**：

            * 使用 OpenTelemetry `Span` 记录工具调用全链路
            * 属性包括工具名、脱敏后的参数、调用结果
        * **Metrics**：

            * 调用次数 (`tool.call.count`)
            * 调用延迟 (`tool.call.latency`)
            * 错误次数 (`tool.call.error.count`)
    * 特性：

        * **参数脱敏**（自动隐藏 password/token 等敏感字段）
        * **结果安全处理**（文件、流、二进制类型特殊标识）
        * **错误分类**（业务异常 vs 系统异常）
        * **全局/工具级开关**（可排除特定工具或关闭可观测）

4. **上下文对象（ToolContext）**

    * 保存工具实例和调用参数
    * 提供给责任链拦截器使用



## 2. 启用可观测性

* 默认情况下，`ToolObservabilityInterceptor` 已全局注册
* 可通过 `Observability.isEnabled()` 或 `Observability.isToolExcluded(toolName)` 动态控制



## 3. 可观测性配置说明

| 配置项                                      | 默认值      | 说明                      |
| - | -- | -- |
| `Observability.isEnabled()`              | true     | 全局开关，可关闭所有工具可观测性        |
| `Observability.isToolExcluded(toolName)` | false    | 可排除指定工具不上报 Span/Metrics |

**Metrics 标签**：

* `tool.name`：工具名称
* `tool.success`：是否成功
* `error.type`：业务异常或系统异常（失败时）


## 4. 总结

`ToolObservabilityInterceptor` + `ToolExecutor` 提供了完整的 **函数调用可观测方案**：

* 自动追踪工具调用链路（Trace）
* 自动收集调用次数、延迟、错误 Metrics
* 支持参数脱敏、结果安全处理
* 异常分类并上报
* 可动态开关、可集成现有监控系统
* 与责任链拦截器无缝结合，业务逻辑无需修改

通过该机制，开发者可以轻松实现 **全链路工具调用监控**，保证可追踪性、安全性和可维护性。




</div>

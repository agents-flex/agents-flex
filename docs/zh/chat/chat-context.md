<div v-pre>

# 对话上下文 ChatContext

## 概述

`ChatContext` 表示一次模型调用在框架内部共享的上下文。它把 `Prompt`、`ChatOptions`、模型配置、已经构建的
HTTP 请求以及业务关联 ID 放在同一个对象中，供对话拦截器、日志和可观测组件协作使用。

它不是聊天历史，也不是跨请求会话存储：

| 概念 | 生命周期 | 用途 |
| --- | --- | --- |
| `ChatContext` | 一次模型调用 | 拦截器间传值、修改当前请求、记录链路 |
| `ChatMemory` | 多轮会话 | 保存历史 Message |
| `ChatOptions.context...` | 一次请求输入 | 提供 ChatContext 使用的业务关联字段 |

## 适用场景

- 多个 ChatInterceptor 共享租户、权限判定或缓存键；
- 在请求发送前添加动态 Header；
- 把 accountId、conversationId、turnId 关联到日志和 Trace；
- 在前置拦截器中计算策略，在后置拦截器中读取；
- 测试中检查最终 Prompt、Options 或 `ChatRequestSpec`。

## 快速开始

业务侧通过 `ChatOptions` 提供关联 ID 和自定义属性：

```java
ChatOptions options = ChatOptions.builder()
    .contextBotId("support-bot")
    .contextAccountId("account-1001")
    .contextConversationId("conversation-2002")
    .contextTurnId("turn-0008")
    .addContextAttributes("plan", "enterprise")
    .build();

AiMessageResponse response = chatModel.chat(prompt, options);
```

框架开始调用时会先创建 `ChatOptions` 的请求级副本。`ChatContext` 不再镜像这些字段，而是直接通过该副本
读取和修改，因此不会出现 Context 与 Options 值不一致的问题。拦截器可以读取：

```java
public class TenantHeaderInterceptor implements ChatInterceptor {
    @Override
    public Object before(ChatContext context, SyncChain chain) {
        String accountId = String.valueOf(context.getAccountId());
        context.getRequestSpec().addHeader("X-Account-Id", accountId);
        return chain.next(context);
    }
}
```

## 核心字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `prompt` | `Prompt` | 当前调用的消息、Tool 和 ToolGroup |
| `config` | `BaseChatConfig` | 当前 ChatModel 的连接与能力配置 |
| `options` | `ChatOptions` | 本次请求独享的生成参数和上下文数据副本 |
| `requestSpec` | `ChatRequestSpec` | 最终 URL、Header 与重试参数 |
| `getBotId()` | `Object` | 从请求级 Options 读取业务 Bot 标识 |
| `getConversationId()` | `Object` | 从请求级 Options 读取连续会话标识 |
| `getAccountId()` | `Object` | 从请求级 Options 读取账号或租户关联标识 |
| `getTurnId()` | `Object` | 从请求级 Options 读取当前单轮交互标识 |
| `getAttributes()` | `Map<String,Object>` | 请求级 Options 中由拦截器共享的扩展属性 |

传给 `chat(...)` 或 `chatStream(...)` 的原始 `ChatOptions` 不会被框架修改。流式标记、拦截器变更和
Context 属性都只写入请求副本，因此同一个 Options 模板可以安全地用于并发调用。基础复制会保留自定义
`ChatOptions` 的运行时类型和扩展字段；扩展字段包含可变集合且需要深度隔离时，子类应覆盖 `copy()` 并调用
`copyBasePropertiesTo(...)`。

AgentRunner 调用模型时还会在请求副本中自动设置当前 AgentTurn 的 `conversationId` 和 `turnId`。
Agent 上配置的 ChatOptions 只是模型参数模板，不应写入固定的 conversationId 或 turnId。

前置拦截器的执行阶段不同。修改 Prompt 和 Options 应在请求序列化前完成；`requestSpec` 只有请求准备完成后才适合
修改。具体阶段顺序见 [对话拦截器](./chat-interceptor)。

## 在拦截器之间传值

```java
public class PlanResolver implements ChatInterceptor {
    @Override
    public Object before(ChatContext context, SyncChain chain) {
        String plan = subscriptionService.findPlan(context.getAccountId());
        context.addAttribute("resolvedPlan", plan);
        return chain.next(context);
    }
}

public class ModelPolicyInterceptor implements ChatInterceptor {
    @Override
    public Object before(ChatContext context, SyncChain chain) {
        Object plan = context.getAttribute("resolvedPlan");
        if ("enterprise".equals(plan)) {
            context.getOptions().setModel("gpt-4o");
        }
        return chain.next(context);
    }
}
```

属性 Key 应使用模块前缀或常量，避免不同拦截器意外覆盖同名数据。

## ChatContextHolder

`ChatContextHolder` 用 `ThreadLocal` 保存当前调用上下文，主要供无法直接接收 Context 参数的日志或观测代码使用：

```java
ChatContext context = ChatContextHolder.currentContext();
if (context != null) {
    audit(context.getConversationId(), context.getTurnId());
}
```

框架通过 `ChatContextScope` 在调用结束后清理。业务通常不需要手动 `beginChat()` 或 `set()`。

ThreadLocal 不会自动传播到业务新建线程、线程池任务或任意异步回调。异步代码需要的字段应显式传递，不要依赖
稍后还能从 `ChatContextHolder` 读取。

`AgentRunner` 会在自己的本地工具执行器中显式绑定本次模型响应的 `ChatContext`，因此工具、ToolInterceptor
和 `aroundToolCall` 可以在该执行范围内读取它；工具内部自行创建的异步任务仍不会自动继承该上下文。

## 修改请求 Header

`BaseModelConfig` 没有通用自定义 Header 配置，动态 Header 可在请求准备完成后的拦截器中加入：

```java
public Object before(ChatContext context, SyncChain chain) {
    ChatRequestSpec spec = context.getRequestSpec();
    if (spec != null) {
        spec.addHeader("X-Tenant-Id", String.valueOf(context.getAccountId()));
        spec.addHeader("X-Request-Token", tokenService.issue());
    }
    return chain.next(context);
}
```

不要把 Token 放入 `attributes` 后输出整个 Context 日志；`ChatContext.toString()` 会包含属性 Map。

## 生产建议

- Context 只保存本次调用所需的小型元数据，不放大文档、二进制或不可序列化连接；
- accountId 和 conversationId 是关联字段，不会自动实施租户权限；
- 拦截器共享属性使用常量 Key，并记录字段所有者；
- 不在日志中直接打印完整 `ChatContext`，其中可能包含 Prompt、配置和 Header；
- 异步任务显式复制所需字段，任务结束后不要持有整个 Context；
- 修改 `requestSpec` 前检查非空，并选择正确的拦截器执行顺序。

## 常见问题

### 为什么 currentContext() 返回 null？

代码可能不在 ChatModel 调用线程中，或调用已经结束并完成清理。拦截器内优先使用方法参数中的 Context。

### contextAttributes 会发送给模型吗？

它们用于框架调用链关联，不会作为普通生成参数发送；但拦截器可以主动用它们修改 Prompt 或请求。

### 可以用 ChatContext 保存多轮历史吗？

不应这样做。多轮历史使用 `ChatMemory`，Context 在一次调用结束后失效。

## 下一步

- [使用对话拦截器](./chat-interceptor)
- [配置 ChatOptions](./chat-model#chatoptions)
- [使用 Memory](./memory)
- [配置日志](./logger)

</div>

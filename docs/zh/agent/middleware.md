---
title: Middleware 扩展
description: 在不修改 Agent 和工具业务代码的情况下，为模型调用与工具调用加入统一处理规则。
---

# Middleware 扩展

## 概述

一个 Agent 通常不只包含“向模型提问”和“执行工具”两件事。应用上线后，往往还会出现一些需要统一
处理的要求，例如：

- 记录每次模型调用花了多长时间；
- 在执行退款、删除等工具前检查当前用户是否有权限；
- 调用模型前，临时补充当前租户的业务说明；
- 对重复的只读查询使用缓存，减少等待时间和调用成本；
- 根据当前用户或任务，决定哪些工具可以使用。

如果把这些逻辑分别写进每个 Agent、每个工具或每次调用中，代码会大量重复，也容易遗漏。
`AgentMiddleware` 用来集中处理这类通用规则。一次模型调用或工具调用经过 Middleware 时，Middleware
可以在执行前进行检查或调整，也可以在执行后记录结果。

可以把它理解为 Agent 执行过程中的“统一检查点”：

```text
Agent 准备执行
       ↓
Middleware 进行统一处理
       ↓
调用模型或执行工具
       ↓
Middleware 记录或整理结果
```

Middleware 适合处理会影响执行过程或执行结果的规则。只需要把运行进度显示到页面、写日志或转发事件，
并且不需要改变执行行为时，应优先使用 [AgentEventListener](./agent-event-listener)。

## 适用场景

| 需求 | Middleware 可以做什么 |
| --- | --- |
| 权限控制 | 在工具执行前检查用户或租户是否有权限，无权限时阻止执行 |
| 统一计时 | 记录模型或工具调用的耗时 |
| 请求调整 | 在调用模型前，为本次请求补充临时说明或可用工具 |
| 结果处理 | 对工具结果进行检查、转换或脱敏 |
| 缓存 | 对参数相同、没有副作用的查询复用已有结果 |
| 动态工具 | 根据当前任务，从工具目录中提供本次允许使用的工具 |

以下情况通常不需要 Middleware：

- 只想接收执行进度、完成或失败通知，使用事件监听器更合适；
- 规则只属于某一个工具，例如“退款金额不能超过订单金额”，应直接写在该工具的业务逻辑中；
- 只是配置工具按顺序还是并行执行，应使用[工具执行控制](./tool-execution)；
- 只是限制任务最多执行多少步或调用多少次工具，应使用[运行限制与预算](./budget)。

## 快速开始

下面先实现一个最简单的 Middleware：统计每次模型调用的耗时。示例中的 `chatModel` 表示已经创建好的
大模型客户端。

### 1. 创建 Middleware

```java
public final class TimingMiddleware implements AgentMiddleware {

    @Override
    public AiMessageResponse aroundModelCall(
        AgentMiddlewareContext context,
        AgentModelCallChain chain) {

        long start = System.nanoTime();
        try {
            return chain.proceed(context);
        } finally {
            long elapsedMs =
                (System.nanoTime() - start) / 1_000_000;
            System.out.println(
                "本次模型调用耗时：" + elapsedMs + " ms");
        }
    }
}
```

这段代码中只有三个需要先理解的对象：

| 代码 | 含义 |
| --- | --- |
| `aroundModelCall(...)` | 每次准备调用大模型时，Runner 都会进入这个方法 |
| `context` | 当前任务的相关信息，例如当前请求和任务数据 |
| `chain.proceed(context)` | 继续执行后续处理，并最终调用大模型 |

`proceed(...)` 返回后，代码会继续执行 `finally` 中的计时记录。即使模型调用失败，`finally` 也会执行，
因此仍然可以记录这次调用花费的时间。生产环境通常应将耗时写入日志或监控系统，而不是直接输出到控制台。
这里使用计时示例，是为了直观展示 Middleware 如何在模型调用前后执行代码。如果框架已有事件能够满足
监控要求，则不必专门编写计时 Middleware。

### 2. 注册到 Agent

```java
Agent agent = Agent.builder("order-agent")
    .instructions("帮助用户查询和处理订单。")
    .chatModel(chatModel)
    .middleware(new TimingMiddleware())
    .build();
```

`.middleware(...)` 表示这个 Agent 每次调用模型时，都要经过 `TimingMiddleware`。Middleware 只有注册到
Agent 后才会生效；创建一个 Middleware 对象不会自动影响其他 Agent。

完成以上配置后，业务代码仍然按照原来的方式运行 Agent，不需要在每次调用模型时手动计时。

## 可以处理哪些位置

`AgentMiddleware` 提供三个处理位置。初次使用时，只需选择和需求最接近的一个方法，不需要全部实现。
没有重写的方法会保持原有执行行为。

| 处理位置 | 对应方法 | 常见用途 |
| --- | --- | --- |
| 一次模型调用 | `aroundModelCall(...)` | 计时、临时调整请求、模型路由、只读缓存 |
| 一次工具调用 | `aroundToolCall(...)` | 权限校验、参数检查、工具计时、结果处理 |
| Agent 的一次处理步骤 | `aroundStep(...)` | 对一个完整处理步骤进行统一控制，属于进阶用法 |

多数业务只需要 `aroundModelCall(...)` 和 `aroundToolCall(...)`。`aroundStep(...)` 覆盖范围更大，错误
使用可能影响 Agent 的正常推进，建议在确实需要控制完整处理步骤时再使用。

## 工具权限校验

假设一个订单 Agent 同时服务多个租户，但并非所有租户都可以使用退款工具。可以在工具真正执行前统一
检查权限：

```java
public final class TenantGuardMiddleware
    implements AgentMiddleware {

    private final PermissionService permissionService;

    public TenantGuardMiddleware(
        PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @Override
    public Object aroundToolCall(
        AgentMiddlewareContext context,
        AgentToolCallChain chain) {

        Object tenantIdValue = context.getRun()
            .getMetadata()
            .get("tenantId");
        if (tenantIdValue == null) {
            throw new SecurityException("缺少租户信息");
        }

        String tenantId =
            String.valueOf(tenantIdValue);
        String toolName =
            context.getToolContext().getToolName();

        if (!permissionService.allowed(tenantId, toolName)) {
            throw new SecurityException("当前租户无权使用该工具");
        }

        return chain.proceed(context);
    }
}
```

这个示例的执行过程是：

1. 从当前任务的 metadata 中读取租户 ID；
2. 读取模型准备调用的工具名称；
3. 交给业务系统自己的 `PermissionService` 检查权限；
4. 有权限时调用 `proceed(...)`，继续执行工具；
5. 没有权限时抛出异常，工具不会执行。

`PermissionService` 不是框架内置类，它代表应用已有的权限服务。`tenantId` 也需要由业务系统在创建任务
时写入 metadata，例如：

```java
AgentTurnOptions options = AgentTurnOptions.builder()
    .metadata("tenantId", "tenant-1001")
    .build();

AgentTurn turn = runner.run(
    agent,
    "为订单 O-1001 申请退款",
    options);
```

metadata 中只应保存租户 ID、用户 ID 等可序列化的业务标识，不要保存密码、访问密钥或服务对象。

注册权限 Middleware：

```java
Agent agent = Agent.builder("order-agent")
    .chatModel(chatModel)
    .middleware(
        new TenantGuardMiddleware(permissionService))
    .build();
```

权限校验属于安全边界。即使模型认为某个工具应该执行，也必须通过业务系统的权限检查，不能把模型输出
本身当作授权依据。

## 多个 Middleware 的执行顺序

同一个 Agent 可以注册多个 Middleware：

```java
Agent agent = Agent.builder("order-agent")
    .chatModel(chatModel)
    .middleware(new TimingMiddleware())
    .middleware(
        new TenantGuardMiddleware(permissionService))
    .build();
```

它们按照注册顺序进入，按照相反顺序退出：

```text
TimingMiddleware 进入
  TenantGuardMiddleware 进入
    调用模型或执行工具
  TenantGuardMiddleware 退出
TimingMiddleware 退出
```

这种结构使外层 Middleware 可以统计内层处理和实际调用的总耗时。

在一般实现中，`chain.proceed(context)` 应当调用一次：

- 调用一次：继续完成正常流程；
- 不调用：后续处理和实际调用都不会执行；
- 调用多次：可能重复请求模型或重复执行工具。

除非明确需要阻止执行或直接提供替代结果，否则不要省略 `proceed(...)`。尤其不要对付款、退款、发货
等会修改业务数据的工具调用多次。

## 临时调整模型请求

在 `aroundModelCall(...)` 中，可以通过 `context.getPrompt()` 读取本次请求，并通过
`context.setPrompt(...)` 替换本次实际发送给模型的 Prompt。

这适合以下场景：

- 根据当前租户，临时补充不同的业务说明；
- 发送给模型前，对敏感信息生成脱敏后的请求；
- 根据当前任务，只向模型提供允许使用的工具；
- 在主模型不可用时，将请求交给备用模型处理。

`setPrompt(...)` 只影响当前这一次调用，不会直接改写已经保存的聊天记录。因此，它适合临时调整，不适合
保存需要在后续对话中长期记住的业务信息。需要长期保留的信息应通过会话记录或可序列化的 metadata 管理，
具体方式见[上下文管理](./context-management)。

Prompt 中可能包含用户输入、历史消息和工具参数。记录日志或发送监控数据前，应先进行脱敏，避免泄露
个人信息、访问令牌和业务机密。

## 缓存与提前返回

Middleware 可以不调用 `proceed(...)`，而是直接返回已有结果。这种方式常用于缓存，但必须谨慎使用。

适合缓存的通常是：

- 不会修改任何数据的查询；
- 相同参数必然得到等价结果的计算；
- 可以接受短时间旧数据的业务场景。

付款、退款、创建订单、发送消息等操作不应通过简单缓存避免重复执行。这些工具需要在业务系统中实现
幂等控制。模型响应的缓存还要完整处理工具调用信息和 Token 用量，初次接入时不建议自行实现。

## 动态工具

有些应用包含几百甚至几千个工具。如果每次都把所有工具说明发送给模型，不仅浪费 Token，模型也更容易
选错工具。这时可以先从工具目录中搜索，再只提供当前任务需要的工具。

Middleware 可以通过 `getToolResolver()` 提供动态工具，并在 `aroundModelCall(...)` 中控制本次让模型
看到哪些工具。这个能力需要同时处理工具展示、名称匹配和任务状态，属于进阶用法。

Agents-Flex 的 `agents-flex-toolsearch` 模块已经提供 `ToolSearchAgentMiddleware`。需要工具搜索时，建议
优先使用现成实现，避免自行处理动态工具的解析和状态保存。

## 如何选择扩展方式

| 需求 | 建议使用 |
| --- | --- |
| 改变模型或工具的执行过程 | `AgentMiddleware` |
| 展示进度、记录事件、发送通知 | `AgentEventListener` |
| 只约束某一个工具的业务规则 | 直接写在该工具中 |
| 为多个 Agent 的底层工具执行加入统一规则 | `ToolInterceptor` |

`AgentMiddleware` 能读取当前 Agent 任务的信息，适合任务级权限、临时请求调整等规则。
`ToolInterceptor` 更接近底层工具执行，适合多个 Agent 共同使用的通用工具处理。刚开始使用时，如果规则
只服务于某个 Agent，优先从 Middleware 开始。

## 错误处理与使用建议

1. 不要捕获异常后返回一个伪造的成功结果，否则模型和业务系统会误以为操作已经完成。
2. 权限不足、参数错误等确定性问题不应自动重试；依赖服务暂时不可用时，才考虑交给重试策略处理。
3. Middleware 对象会被多个任务重复使用，不要把某个用户或某次任务的可变数据保存在实例字段中。
4. 计时、日志和监控代码也会增加请求耗时，应避免在 Middleware 中执行长时间阻塞操作。
5. 测试时至少覆盖正常通过、权限拒绝、下游异常和多个任务并发执行四种情况。

## 相关文档

- 监听执行过程而不改变结果：[AgentEventListener](./agent-event-listener)
- 配置工具的顺序、并行和结果大小：[工具执行控制](./tool-execution)
- 配置失败后的重试行为：[错误处理与重试](./retry)
- 在工具中读取当前任务信息：[工具运行上下文](./tool-context)
- 了解会话记录和模型上下文：[上下文管理](./context-management)

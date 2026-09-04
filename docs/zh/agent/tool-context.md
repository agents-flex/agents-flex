---
title: 工具运行上下文（AgentToolContext）
description: 在工具执行过程中获取调用标识、避免重复操作、上报进度，并响应用户取消请求。
---

# 工具运行上下文（AgentToolContext）

## 概述

模型调用工具时，通常只会提供完成业务所需的参数。例如，创建工单时，模型可能提供工单标题、问题描述
和优先级。但是，一个可以安全上线的工具往往还需要知道：

- 这次工具调用属于哪个任务，方便关联日志和排查问题；
- 这是不是同一次操作的再次执行，避免重复创建工单或重复扣款；
- 用户是否已经要求取消任务；
- 长时间执行时，怎样把当前进度显示到页面；
- 用户补充表单后，本次提交了哪些数据。

这些信息不属于用户的业务参数，也不应该由模型填写。`AgentToolContext` 就是框架在执行当前 Java 应用
中的工具时，自动提供的一组运行信息和辅助能力。

可以把工具接收到的信息分成两类：

| 信息 | 来源 | 示例 |
| --- | --- | --- |
| 业务参数 | 由模型根据用户要求生成 | 工单标题、订单号、退款金额 |
| 运行上下文 | 由框架自动提供 | 调用 ID、幂等键、取消状态、进度上报能力 |

普通查询工具通常只使用业务参数即可。涉及写数据库、调用外部服务、长时间处理或表单恢复时，才需要使用
工具运行上下文。

## 适用场景

| 场景 | 使用上下文解决的问题 |
| --- | --- |
| 创建订单、退款、发货 | 获取稳定的幂等键，防止同一操作被重复执行 |
| 生成报告、批量导入 | 定期上报进度，并在用户取消后尽快停止 |
| 日志和问题排查 | 使用任务 ID 和工具调用 ID 关联整条执行记录 |
| 工具中动态收集信息 | 读取用户提交的表单数据 |
| 自动重试或任务恢复 | 判断工具是否再次执行，以及本次恢复的原因 |

`AgentToolContext` 只服务于当前正在执行的工具，不用于修改 Agent 配置，也不负责启动、暂停或恢复整个
任务。

## 快速开始

下面以“创建售后工单”为例。创建工单属于写操作：如果任务因为临时故障再次执行，应用必须避免创建两张
内容相同的工单。

### 1. 在工具中获取上下文

```java
Tool createTicketTool = Tool.builder(
        "create_ticket",
        "根据用户提供的信息创建售后工单")
    .addParameter(Parameter.builder()
        .name("subject")
        .type("string")
        .description("工单标题")
        .required(true)
        .build())
    .addParameter(Parameter.builder()
        .name("description")
        .type("string")
        .description("问题描述")
        .required(true)
        .build())
    .function(arguments -> {
        AgentToolContext context =
            AgentToolContext.current();

        if (context == null) {
            throw new IllegalStateException(
                "当前工具不在 AgentRunner 中执行");
        }

        String idempotencyKey =
            context.getIdempotencyKey();

        return ticketService.createOnce(
            arguments,
            idempotencyKey);
    })
    .build();
```

`AgentToolContext.current()` 获取当前工具的运行上下文。`AgentRunner` 是框架中负责运行 Agent 的执行器；
工具由它正常执行时，框架会自动准备上下文对象，不需要业务代码自己创建或传入。

`subject` 和 `description` 是模型需要填写的业务参数，因此通过 `Parameter` 定义；`idempotencyKey` 是
Runner 生成的运行信息，因此从 `AgentToolContext` 读取，不应把它声明成让模型填写的工具参数。

示例中的 `ticketService.createOnce(...)` 代表应用自己的工单服务，不是框架内置方法。它需要保证：同一个
`idempotencyKey` 无论收到多少次，都只创建一张工单，并返回第一次创建的结果。

### 2. 将工具注册到 Agent

```java
Agent agent = Agent.builder("support-agent")
    .instructions(
        "你是售后助手。信息完整后，可以调用工具创建工单。")
    .chatModel(chatModel)
    .tool(createTicketTool)
    .build();
```

这里的 `chatModel` 表示已经创建好的大模型客户端。工具注册到 Agent 后，只要模型选择
`create_ticket`，工具函数就可以通过 `AgentToolContext.current()` 读取本次运行信息，不需要额外配置。

## 常用能力

初次使用时，优先关注下面这些方法：

| 方法 | 作用 |
| --- | --- |
| `getTurnId()` | 获取当前任务 ID，用于关联日志或业务记录 |
| `getToolCallId()` | 获取本次工具调用 ID，用于区分同一任务中的不同工具调用 |
| `getToolName()` | 获取当前工具名称 |
| `getIdempotencyKey()` | 获取稳定的幂等键，用于防止写操作重复执行 |
| `emitProgress(...)` | 上报工具当前执行进度 |
| `isCancellationRequested()` | 检查用户是否已经请求取消任务 |
| `getSubmittedFormData()` | 读取用户为当前工具提交的表单数据 |

还可以通过 `getAgentId()` 和 `getAgentVersion()` 获取本次任务使用的 Agent 标识及版本。它们适合写入
日志和审计记录，一般不参与工具的业务判断。

## 防止重复执行

工具调用外部系统时，可能出现这样的情况：

```text
工单已经创建成功
       ↓
应用在保存执行结果前发生故障
       ↓
任务恢复后再次进入同一个工具
```

如果工具直接再次调用工单系统，就会创建重复数据。`getIdempotencyKey()` 为同一个任务中的同一次工具调用
提供稳定标识，即使工具再次执行，这个值也保持不变。

```java
AgentToolContext context = AgentToolContext.current();

String ticketId = ticketService.createOnce(
    arguments,
    context.getIdempotencyKey());
```

幂等必须由业务系统真正实现，不能只读取这个键：

- 外部服务支持幂等键时，把该值随请求一起发送；
- 写入自己的数据库时，为幂等键建立唯一约束，并保存第一次执行的结果；
- 收到相同幂等键时，返回第一次的结果，不要再次产生业务影响。

不要只在 JVM 内存中保存“已经执行”的标记。应用重启、多实例部署或其他 Worker 接管任务后，这类标记
会丢失。

::: warning 写操作必须考虑幂等
退款、扣款、创建订单、发货、发送邮件等操作都可能产生外部影响。即使没有主动配置重试，也应按可能
再次执行来设计。
:::

## 上报执行进度

生成报告、批量导入和文件处理可能需要较长时间。如果页面只能等待最终结果，用户容易误以为任务已经
卡住。工具可以在关键阶段主动上报进度：

```java
AgentToolContext context = AgentToolContext.current();

context.emitProgress(
    "正在读取数据",
    Collections.singletonMap("percent", 20));

Report report = reportService.build(arguments);

context.emitProgress(
    "正在生成文件",
    Collections.singletonMap("percent", 80));
```

第一个参数是便于展示的进度说明，第二个参数是可选的结构化数据。业务系统可以通过事件监听器接收这些
进度，并将它们发送到 WebSocket、消息队列或日志系统，具体方式见
[AgentEventListener](./agent-event-listener)。

进度信息只用于展示和观察，不会改变工具的最终返回值。不要在进度数据中放入密码、令牌、完整证件号等
敏感内容。

## 响应取消请求

用户点击“取消”后，Runner 无法强行中断一段正在执行的 Java 代码。长时间运行的工具需要在分页、批处理
或轮询之间主动检查取消状态：

```java
AgentToolContext context = AgentToolContext.current();

for (int page = 1; page <= totalPages; page++) {
    if (context.isCancellationRequested()) {
        return "任务已停止，未继续处理剩余数据";
    }

    importService.processPage(page);
    context.emitProgress(
        "已处理第 " + page + " 页");
}

return "数据导入完成";
```

取消检查应放在可以安全停止的位置。例如，一条数据库事务执行到一半时，不应直接留下部分数据；应先
回滚或完成当前最小操作单元，再尽快结束工具。调用外部服务时，如果对方提供取消接口，也应由工具负责
调用。

短时间查询通常不需要频繁检查。循环次数很多时，也不必每处理一条数据就检查一次，可以按页或按批次
检查，避免不必要的开销。

## 读取表单数据

少数工具只有在查询业务规则后，才能确定还需要用户补充哪些信息。例如，创建企业账户时，工具查询客户
类型后才知道需要税号和开票信息。

这类工具可以请求表单。用户提交后，同一个工具会重新开始执行，此时可以读取提交的数据：

```java
AgentToolContext context = AgentToolContext.current();
Map<String, Object> values =
    context.getSubmittedFormData();

String taxNumber =
    (String) values.get("taxNumber");
```

第一次执行且用户尚未提交表单时，`getSubmittedFormData()` 返回空 Map。表单定义、请求和提交方式见
[表单输入](./form-input)。

工具在提交表单后会从头执行，而不是从上次停止的位置继续。因此，应在扣款、写数据库等操作之前请求
表单，并且仍要使用幂等键保护后续写操作。

## 进阶：识别再次执行与恢复来源

排查重试、审批或表单恢复问题时，可以读取更详细的执行信息：

| 方法 | 含义 |
| --- | --- |
| `getExecutionAttempt()` | 当前工具函数是第几次开始执行，从 1 开始 |
| `isReplay()` | 当前工具函数是否已经执行过至少一次 |
| `isResumed()` | 本次执行是否由恢复操作触发 |
| `isFormInputResumed()` | 是否在用户提交表单后恢复 |
| `isApprovalResumed()` | 是否在审批通过后恢复 |
| `isRetryResumed()` | 是否因为上一次执行失败而重试 |
| `getResumeCount()` | 当前工具调用累计恢复了多少次 |
| `getRetryAttempt()` | 本次错误重试的序号；不是错误重试时为 0 |

这些信息主要用于日志、监控和问题排查。不要用 `isReplay()` 代替业务幂等：工具第二次执行时，第一次操作
可能已经成功，也可能根本没有发生，单凭执行次数无法判断外部系统的真实状态。

需要查看最近一次恢复的完整信息时，可以使用 `getResumeInfo()`。其中可能包含前一次错误类型、错误消息
以及业务附加的审批或表单信息。记录错误消息前应先脱敏。

## 使用范围

`AgentToolContext` 只在 `AgentRunner` 正在执行当前本地工具时有效：

- 在工具函数中调用 `AgentToolContext.current()`，可以获得当前上下文；
- 在普通业务代码或直接调用工具函数时，`current()` 可能返回 `null`；
- 工具执行结束后，不要继续保存或使用这个上下文对象；
- 上下文不会自动传递到工具自己创建的异步线程。

如果工具必须启动异步处理，应在提交异步任务前，只复制真正需要的字符串值，例如 `turnId`、
`toolCallId` 和 `idempotencyKey`。不要把整个 `AgentToolContext` 保存到数据库、缓存、静态字段或异步任务中。

## 与其他能力的区别

| 需求 | 应使用的能力 |
| --- | --- |
| 读取当前工具的调用标识、幂等键和表单数据 | `AgentToolContext` |
| 在模型或工具调用前后加入统一规则 | `AgentMiddleware` |
| 接收进度、完成、失败等运行事件 | `AgentEventListener` |
| 请求、恢复或取消整个任务 | 业务层调用 `AgentRunner` |
| 读取模型生成的业务参数 | 工具函数的 `arguments` 参数 |

`AgentToolContext` 提供的是只读运行信息和受控辅助能力。它不能直接修改任务状态，也不能替代业务系统的
权限校验、事务和幂等实现。

## 使用建议

1. 只在工具执行期间获取和使用上下文，不要长期保存整个对象。
2. 所有会产生外部影响的工具都应使用稳定幂等键。
3. 长任务在安全边界检查取消状态，并在关键阶段上报进度。
4. 不要把密码、密钥和敏感业务数据写入进度、日志或恢复信息。
5. 测试工具时，至少覆盖首次执行、重复执行、取消请求和异常恢复。

## 相关文档

- 在工具中动态收集用户信息：[表单输入](./form-input)
- 了解任务如何暂停和继续：[挂起和恢复](./suspend-resume)
- 接收工具进度和执行事件：[AgentEventListener](./agent-event-listener)
- 为模型和工具调用加入统一规则：[Middleware 扩展](./middleware)
- 配置工具失败后的重试行为：[错误处理与重试](./retry)

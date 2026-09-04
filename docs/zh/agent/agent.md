---
title: Agent 配置
description: 了解 Agent 是什么、解决什么问题，以及如何为 AI 助手配置模型、指令、工具和安全规则。
---

# Agent 配置

## 概述

`Agent` 是一份可以重复使用的 AI 助手配置，用于统一描述助手所使用的模型、需要遵守的指令、可以调用的 Java 工具，以及执行过程中的安全和资源限制。

一个 Agent 通常包含以下内容：

- 使用哪个大模型；
- 应该遵守什么指令；
- 可以调用哪些 Java 工具；
- 哪些操作必须先让人确认；
- 最多可以执行多少次、消耗多少 Token。

Token 是大模型统计文本用量的基本单位，也通常会影响调用费用。

只调用大模型时，模型通常只能根据输入生成文字。真实业务往往还需要查询数据库、调用接口、执行操作，并限制模型可以做什么。

如果没有 Agent，这些模型配置、工具和安全规则容易散落在不同代码中。任务一多，就会遇到下面的问题：

- 同一种助手在不同地方使用了不同的指令或工具；
- 危险操作忘记增加审批；
- 模型连续调用工具，缺少次数和费用限制；
- 服务重启后，不知道应该用哪一版配置继续旧任务。

`Agent` 把这些内容组合成一个明确、可复用的配置。业务系统创建一次 Agent 后，可以用它处理很多用户任务，并保证这些任务遵守相同的基本规则。

例如，一个订单助手可以配置为：使用指定模型，回答前先查询订单，可以调用“查询订单”和“申请退款”两个工具，并且退款前必须经过人工审批。

从使用角度看，Agent 相当于一份“岗位说明书和工具清单”。它定义助手的能力和工作规则，但不保存某次任务的运行状态，也不会自行执行任务。

## 与相关对象的关系

Agent 运行过程中会涉及以下几个对象：

| 对象 | 职责 |
| --- | --- |
| `ChatModel` | 连接大模型服务的 Java 接口，例如连接 OpenAI 或其他兼容服务 |
| `Tool` | 提供给大模型选择的 Java 能力，例如查询订单或申请退款 |
| `Agent` | 把模型、指令、工具和规则组合起来的助手配置 |
| `AgentRunner` | 真正执行任务的运行器 |
| `AgentTurn` | 某一次用户任务的状态和结果 |

`Agent` 通过 `ChatModel` 使用大模型服务，通过 `Tool` 声明可以调用的业务能力。它本身不代表大模型服务，也不直接执行工具。

业务系统将 Agent 交给 `AgentRunner` 执行。每次用户提交新任务时，Runner 都会创建一个新的 `AgentTurn`，用于记录该任务的状态、消息和结果。因此，Agent 是可复用配置，AgentTurn 才是一次具体任务。

## 基本创建方式

假设已经创建好 `chatModel` 和 `queryOrderTool`，可以这样创建订单助手：

```java
Agent agent = Agent.builder("order-assistant")
    .description("帮助用户查询订单")
    .instructions("回答订单问题前，必须先调用 query_order 工具查询真实数据。")
    .chatModel(chatModel)
    .tool(queryOrderTool)
    .build();
```

每一行配置的含义如下：

| 配置 | 含义 |
| --- | --- |
| `builder("order-assistant")` | 创建 Agent，并设置名称 |
| `description(...)` | 用一句话说明它能做什么 |
| `instructions(...)` | 告诉大模型应该遵守哪些工作规则 |
| `chatModel(...)` | 指定它使用的大模型 |
| `tool(...)` | 添加一个模型可以选择的 Java 工具 |
| `build()` | 完成创建。创建后配置不能直接修改 |

创建 Agent 后，再交给 `AgentRunner` 执行用户任务：

```java
AgentRunner runner = new AgentRunner();
AgentTurn turn = runner.run(agent, "帮我查询订单 A1001 的状态");
```

每调用一次 `run(...)`，都会创建一个新的 `AgentTurn`。同一个 `agent` 可以继续用于其他用户或其他任务。

完整可运行的项目请查看[快速开始](./getting-started)。

## 标识信息

```java
Agent agent = Agent.builder("order-assistant")
    .id("order-assistant")
    .version("1")
    .description("查询订单并处理售后请求")
    .chatModel(chatModel)
    .build();
```

这几个字段用途不同：

| 字段 | 用途 |
| --- | --- |
| `name` | Agent 的名称，主要方便人阅读和页面展示 |
| `id` | 程序识别 Agent 的稳定标识，保存任务后不要随意修改 |
| `version` | Agent 配置的版本，用来区分新旧配置 |
| `description` | 简要说明 Agent 能做什么 |

调用 `Agent.builder("order-assistant")` 时，传入的是 `name`。如果没有单独设置 `id`，默认会使用 `name`；如果没有设置 `version`，默认为 `"1"`。

本地示例可以先使用默认值。需要保存并恢复长任务时，建议明确设置稳定的 `id` 和 `version`。

## 系统指令

`instructions(...)` 会把工作要求发送给大模型。它适合说明：

- Agent 的身份和任务；
- 回答时应该遵守的规则；
- 什么情况下应该调用工具；
- 哪些事情不能猜测或不能执行。

例如：

```java
.instructions(
    "你是订单售后助手。"
    + "回答订单状态前必须调用 query_order。"
    + "没有查询到数据时直接说明，不要编造订单信息。"
)
```

指令应该具体、直接。与其写“请正确处理订单”，不如明确写出“先查询订单”“不要编造数据”等可以执行的规则。

指令只能告诉模型“应该怎么做”，不能凭空增加能力。如果希望 Agent 查询订单，还必须注册真正的查询工具。

## 工具配置

`Tool` 是一段可以被 Agent 调用的 Java 逻辑。它可以查询数据库、调用第三方接口，也可以执行你自己的业务方法。

```java
Agent agent = Agent.builder("order-assistant")
    .chatModel(chatModel)
    .tool(queryOrderTool)
    .tool(refundOrderTool)
    .build();
```

大模型会根据工具的名称、说明和参数定义来选择工具。因此，工具说明必须清楚地表达“什么时候使用”和“需要哪些参数”。同一个 Agent 中的工具名称不能重复。

::: warning 工具权限仍由业务系统控制
把工具添加到 Agent，不代表可以跳过用户身份、订单归属或业务状态检查。退款、删除、发货等有实际影响的工具，仍应在 Java 代码中校验权限和参数。
:::

## 工具审批

会真正修改数据或触发业务操作的工具，可以配置审批规则。下面的示例要求执行退款工具前先等待人工确认：

```java
Agent agent = Agent.builder("order-assistant")
    .chatModel(chatModel)
    .tool(queryOrderTool)
    .tool(refundOrderTool)
    .toolApprovalPolicy((turn, call, tool) ->
        "refund_order".equals(tool.getName())
            ? ToolApprovalDecision.requireApproval()
                .message("退款需要人工确认")
                .build()
            : ToolApprovalDecision.ALLOW)
    .build();
```

当模型选择 `refund_order` 时，`AgentRunner` 不会立即执行工具，而是暂停当前任务。业务系统收到用户的同意或拒绝后，再恢复原来的 `AgentTurn`。

具体用法请查看[人工审批](./human-approval)。

## 执行限制

Agent 可能多次调用模型和工具。可以使用 `AgentExecutionPolicy` 设置限制，避免任务长时间循环或产生不可控费用。

```java
AgentExecutionPolicy policy = AgentExecutionPolicy.builder()
    .maxIterations(8)
    .maxSteps(32)
    .budget(AgentBudget.builder()
        .maxToolCalls(10)
        .maxTotalTokens(20_000)
        .maxDurationMillis(60_000)
        .build())
    .build();

Agent agent = Agent.builder("order-assistant")
    .chatModel(chatModel)
    .tool(queryOrderTool)
    .executionPolicy(policy)
    .build();
```

上面的配置表示：最多调用模型 8 次，整个任务最多推进 32 个步骤，最多调用 10 次工具、消耗 20,000 个 Token，并且最长运行 60 秒。

具体配置请查看[运行限制与预算](./budget)和[错误处理与重试](./retry)。

## 可选能力

先完成一个简单 Agent，再根据业务需要使用下面的能力：

| 配置 | 适用场景 |
| --- | --- |
| `chatOptions(...)` | 设置模型名称、温度、最大输出 Token 等参数 |
| `multimodalChatModel(...)` | 输入中包含图片、音频、视频或文件时使用另一个模型 |
| `modelSelector(...)` | 根据本次任务动态选择模型 |
| `toolGroup(...)` | 只在符合条件的请求中向模型提供某组工具 |
| `toolVisibilityPolicy(...)` | 按本次请求控制哪些工具对模型可见 |
| `maxAttachedTurns(...)` | 限制发送给模型的历史任务轮数 |
| `maxAttachedMessages(...)` | 限制发送给模型的历史消息数量 |
| `maxAttachedTokens(...)` | 按 Token 数量限制模型上下文 |
| `compressionPolicy(...)` | 压缩较早的聊天历史，减少上下文用量 |
| `middleware(...)` | 在模型或工具调用前后加入统一处理逻辑 |
| `attribute(...)` | 添加供业务系统读取的标签或扩展信息 |

这些都是可选项，不影响最基本的 Agent 创建和运行。

## 版本管理

运行中的任务可能暂停几分钟甚至几天。任务恢复时，应该继续使用它创建时对应的 Agent 配置，否则可能出现工具已经改名、参数已经变化等问题。

Agents-Flex 保存任务进度时，会记录 Agent 的 `id` 和 `version`。恢复任务时，`AgentLoader` 根据这两个值找回对应配置。可以把 `AgentLoader` 理解为“按编号和版本查找 Agent 的组件”。

下面这些变化通常应该发布新版本：

- 删除或重命名工具；
- 修改工具参数；
- 改变退款、发布等工具的业务含义；
- 修改会影响旧任务继续执行的审批或扩展逻辑。

如果应用不需要保存和恢复任务，可以暂时不处理复杂的版本管理。需要接入时请查看 [AgentLoader](./agent-loader)。

## 复用与线程安全

可以。`build()` 完成后，Agent 的配置不能直接修改，因此适合在应用中重复使用。

但 Agent 内部引用的 `ChatModel`、`Tool` 和 Middleware 也可能被多个任务同时调用。这些对象不应该把某个用户或某次任务的数据保存在共享成员变量中。需要长期保存的任务数据，应放到 `AgentTurn` 或外部数据库。

如果需要修改 Agent 配置，请创建一个新的 Agent，而不是尝试修改已经构建完成的对象。

## 下一步

- 运行第一个完整示例：[快速开始](./getting-started)。
- 了解任务如何执行：[AgentRunner](./agent-runner)。
- 了解一次任务保存什么：[AgentTurn](./agent-turn)。
- 为工具增加人工确认：[人工审批](./human-approval)。
- 保存并恢复不同版本的 Agent：[AgentLoader](./agent-loader)。

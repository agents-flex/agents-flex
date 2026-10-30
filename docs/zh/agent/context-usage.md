---
title: 上下文 Token 使用与自动压缩
description: 使用模型服务返回的 Usage 展示上下文占用，并在达到阈值或发生上下文超限时自动压缩和恢复。
---

# 上下文 Token 使用与自动压缩

## 概述

持续对话运行一段时间后，模型每次请求携带的历史会越来越长。应用通常需要回答以下问题：

- 最近一次模型调用确认消耗了多少 Token？
- 按当前模型的上下文容量，还剩多少可用空间？
- 上下文接近上限时，能否在下一次调用前自动压缩较早历史？
- 如果供应商直接返回“上下文超限”，能否先压缩再重试？

Agents-Flex 通过 `AgentContextUsagePolicy` 提供这套能力。它只信任模型供应商在响应中返回的 Usage，
不会使用字符数、分词器或自适应比例进行本地 Token 估算。这样可以避免页面展示一个看似精确、实际上
与供应商计费或上下文计算方式不一致的数字。

这项功能由两部分协作完成：

1. **Usage 状态管理**：保存最近一次服务端确认的 Token 基准，计算占用比例和剩余量。
2. **持久化上下文压缩**：达到阈值时，在下一次真实模型调用前将较早历史压缩为摘要；发生可恢复的
   上下文超限异常时，也可以压缩后重试。

::: tip 与本地 Token 窗口的区别
`maxAttachedTokens(...)` 和增量压缩中的 `tokenEstimator` 属于本地估算能力；本页介绍的
`AgentContextUsagePolicy` 只使用供应商确认的 Usage。两套机制可以独立使用，也可以同时配置，但前端展示的
“已确认 Token”不应混入本地估算值。
:::

## 适用场景

| 场景 | 需要的能力 | 建议 |
| --- | --- | --- |
| 长期客服会话 | 展示已确认占用，接近上限时自动摘要 | 配置 85%～90% 阈值，保留最近 2～4 轮 |
| 项目协作助手 | 保存早期决定，同时控制长期上下文 | 使用持久化摘要，重点保留结论、负责人和约束 |
| 工具密集型 Agent | 同一 Turn 内多次调用模型，历史增长快 | 开启阈值压缩，工具返回后的下一次调用也会检查 |
| 多实例服务 | 重启或切换实例后继续使用相同基准 | 使用 JDBC 或 Redis 保存 Usage 与压缩状态 |
| 供应商不返回 Usage | 无法可靠展示比例，但仍要处理超限 | 开启异常恢复，不展示估算用量 |

以下情况通常不需要启用：

- 单轮请求或不带 `conversationId` 的任务；
- 对话很短，历史不会接近模型上下文上限；
- 模型容量未知，且供应商也不会返回可识别的上下文超限异常；
- 业务只需要本地粗略裁剪，不需要展示服务端确认用量。

## 工作原理

### 1. 服务端 Usage 建立基准

模型调用成功后，框架从 `AiMessage` 中读取供应商返回的 Usage。满足以下任意条件时，才会建立确认基准：

- 返回了 `totalTokens`；
- 没有 `totalTokens`，但同时返回了 `promptTokens` 和 `completionTokens`。

只有输入或只有输出属于不完整 Usage，不会建立新基准。如果之前已经有确认基准，而后续成功调用没有返回
完整 Usage，旧基准也会失效，因为本轮输出已经改变了下一次请求的上下文。

`localPromptTokens`、`localCompletionTokens` 和 `localTotalTokens` 不参与这套计算。

### 2. 计算可用输入容量

模型的总上下文需要同时容纳输入和输出，因此框架先为输出预留空间：

```text
可用输入容量 = contextWindowTokens - reservedOutputTokens
占用比例     = nextCallBaselineTokens / 可用输入容量
剩余量       = 可用输入容量 - nextCallBaselineTokens
```

例如，模型总上下文为 128,000 Token，为输出预留 8,192 Token，则可用输入容量为 119,808 Token。
最近一次响应确认的下一轮基准为 100,000 Token 时，占用比例约为 83.47%，剩余基准容量为 19,808 Token。

`nextCallBaselineTokens` 是最近一次供应商响应能够确认的基准。它不包含尚未发送的新用户输入、后续新增的
`ToolMessage` 或其他本地待发送内容，因此 UI 应标注为“最近确认值”，而不是“当前输入后的精确值”。

### 3. 达到阈值后延迟压缩

当占用比例达到 `compressionThresholdRatio` 时，框架不会在当前响应完成后立刻压缩，而是保存
`compressionRequired=true`，并发布 `CONTEXT_COMPRESSION_REQUIRED` 事件。

下一次真实模型调用前，Runner 会消费这个标记并执行持久化压缩：

```text
模型成功响应并返回完整 Usage
        ↓
达到阈值，记录“下次调用前压缩”
        ↓
当前响应正常返回给业务
        ↓
下一次真实模型调用前压缩较早历史
        ↓
使用“摘要 + 最近原始对话”调用模型
```

这里的“下一次真实模型调用”既可能是用户下一轮消息，也可能是同一 Turn 中工具执行完成后的第二次模型调用。

### 4. 压缩后基准为什么会失效

压缩会把“较早原始历史”替换为“摘要”，模型可见上下文已经发生变化。压缩前的 Token 数值仍可保留用于审计，
但不能继续代表压缩后的真实占用。因此状态会变为 `BASELINE_INVALIDATED`，且不再提供可靠的
`baselineRatio` 和 `remainingBaselineTokens`。

下一次模型调用成功并返回完整 Usage 后，状态才会重新变为 `CONFIRMED_BASELINE`。

| 状态 | 含义 | 可展示比例和剩余量 |
| --- | --- | --- |
| `NO_CONFIRMED_USAGE` | 尚未获得完整服务端 Usage | 否 |
| `CONFIRMED_BASELINE` | 最近 Usage 与当前模型可见上下文一致 | 是 |
| `BASELINE_INVALIDATED` | 压缩或 Usage 缺失后，旧基准已失效 | 否 |

::: warning 不要删除完整 ChatMemory
压缩只改变发送给模型的历史视图，不会删除或覆盖完整 `ChatMemory`。原始会话记录仍可用于审计、客服查询或
其他业务用途。
:::

## 快速开始

下面以 OpenAI 兼容模型和 JDBC Store 为例，配置一个达到 90% 时自动压缩的持续会话 Agent。

### 1. 配置模型容量

```java
OpenAIChatConfig chatConfig = OpenAIChatConfig.builder()
    .apiKey(apiKey)
    .model("gpt-4.1")
    .contextWindowTokens(128_000)
    .maxOutputTokens(8_192)
    .build();

ChatModel chatModel = chatConfig.toChatModel();
```

| 配置 | 含义 |
| --- | --- |
| `contextWindowTokens(...)` | 模型输入与输出合计的上下文容量；必须以实际供应商规格为准 |
| `maxOutputTokens(...)` | 模型允许的最大输出 Token；也可作为默认输出预留 |

框架不会在线查询模型规格，也不会猜测容量。模型升级、别名切换或代理服务限制发生变化时，应同步更新配置。

### 2. 准备状态 Store

```java
JdbcAgentStoreConfig storeConfig =
    JdbcAgentStoreConfig.builder(dataSource).build();

storeConfig.schema().initialize();

AgentContextUsageStateStore usageStateStore =
    storeConfig.usageStateStore();

AgentContextCompressionStateStore compressionStateStore =
    storeConfig.compressionStateStore();
```

Usage Store 保存服务端确认基准；Compression Store 保存摘要和压缩游标。生产环境应使用数据库迁移工具管理
Schema。JDBC 默认表名包含 `context_usage_states`，配置 `tablePrefix(...)` 后会添加对应前缀。

### 3. 配置持久化压缩

```java
AgentContextCompressionPolicy compressionPolicy =
    AgentContextCompressionPolicy.builder()
        .stateStore(compressionStateStore)
        .compressor(AgentContextCompressors.model(
            summaryModel,
            "请总结较早历史，保留事实、业务 ID、数字、约束、"
                + "处理结论和未完成事项；不要编造信息。"))
        .keepRecentTurns(3)
        .compressionFailureStrategy(
            AgentCompressionFailureStrategy.FAIL)
        .build();
```

启用 Usage 策略时，压缩策略必须至少配置 `stateStore` 和 `compressor`，从而保证摘要和游标可以持久化。
不需要同时配置本地 `tokenEstimator` 或 `decider`；Usage 阈值和服务端异常会直接强制触发压缩。

### 4. 配置 Usage 策略

```java
AgentContextUsagePolicy usagePolicy =
    AgentContextUsagePolicy.builder()
        .enabled(true)
        .stateStore(usageStateStore)
        .compressionThresholdRatio(0.90d)
        .reservedOutputTokens(8_192)
        .compressOnTokenLimitExceeded(true)
        .maxTokenLimitRecoveryAttempts(1)
        .build();

Agent agent = Agent.builder("support-agent")
    .chatModel(chatModel)
    .compressionPolicy(compressionPolicy)
    .contextUsagePolicy(usagePolicy)
    .build();
```

Usage 策略默认关闭。启用后如果缺少持久化压缩策略，`Agent.build()` 会直接拒绝该配置，避免运行时才发现
无法执行压缩。

### 5. 使用 conversationId 持续对话

```java
AgentRunner runner = AgentRunner.builder()
    .chatMemoryProvider(chatMemoryProvider)
    .build();

AgentTurn first = runner.run(
    agent,
    "conversation-1001",
    "我的订单还没有退款，请帮我查询");

AgentTurn next = runner.run(
    agent,
    "conversation-1001",
    "继续处理刚才的问题");
```

Usage 状态以 `conversationId` 保存，因此这项功能只适用于带会话 ID 的持续会话。没有 `conversationId` 的
单次 `runner.run(...)` 不会持久化 Usage 基准，也不会执行基于会话历史的自动压缩。

## 前端如何展示

### 监听模型完成事件

`MODEL_COMPLETED` 会附带本次服务端 Usage、模型容量和 Usage 状态：

```java
runner.addEventListener(event -> {
    if (event.getType() != AgentEventType.MODEL_COMPLETED) {
        return;
    }

    Map<String, Object> data = event.getData();
    Object status = data.get("usageStatus");
    boolean valid = Boolean.TRUE.equals(data.get("usageBaselineValid"));

    if (valid) {
        usageView.update(
            conversationId,
            ((Number) data.get("nextCallBaselineTokens")).longValue(),
            ((Number) data.get("remainingBaselineTokens")).longValue(),
            ((Number) data.get("baselineRatio")).doubleValue());
    } else {
        usageView.markUnconfirmed(conversationId, status);
    }
});
```

示例中的 `conversationId` 由业务在注册监听器的上下文中持有；如果一个 Runner 服务多个会话，业务可以通过
`turnId` 关联自己的会话表。`usageView` 表示业务自己的 WebSocket、SSE、状态缓存或 UI 更新服务。事件监听器
本身不会自动把数据推送到浏览器。

### MODEL_COMPLETED 关键字段

| 字段 | 含义 |
| --- | --- |
| `inputTokens` | 本次供应商返回的输入 Token |
| `outputTokens` | 本次供应商返回的输出 Token |
| `totalTokens` | 本次供应商返回的总 Token |
| `usageSource` | `PROVIDER_CONFIRMED` 或 `UNAVAILABLE` |
| `modelKey` | 当前实际模型的稳定标识 |
| `contextWindowTokens` | 配置的模型总上下文容量 |
| `modelMaxOutputTokens` | 配置的模型最大输出 Token |
| `reservedOutputTokens` | 本次计算采用的输出预留 |
| `nextCallBaselineTokens` | 最近确认的下一次调用基准 |
| `baselineRatio` | 已确认基准占可用输入容量的比例 |
| `remainingBaselineTokens` | 已确认基准下的剩余容量 |
| `compressionRequired` | 是否将在下一次真实模型调用前压缩 |
| `usageBaselineValid` | 比例和剩余量是否仍可展示 |
| `usageStatus` | 当前 Usage 基准状态 |

`baselineRatio` 和 `remainingBaselineTokens` 只会在基准有效且模型容量已知时出现。前端不要把字段缺失
当作 0，也不要用历史 Token 自行补算。

### 推荐的 UI 状态

| 条件 | 推荐展示 |
| --- | --- |
| `CONFIRMED_BASELINE` | “最近确认：83.5%，剩余约 19,808 Token” |
| `compressionRequired=true` | “已接近上限，将在下一次模型调用前压缩” |
| `BASELINE_INVALIDATED` | “上下文已压缩，等待下一次服务端 Usage 确认” |
| `NO_CONFIRMED_USAGE` | “模型未返回可用 Token Usage” |
| 容量未知 | 展示本次 Usage，但不展示比例和剩余量 |

不要根据用户正在输入的文本实时增加这个数字。当前功能没有本地 Token 估算器，用户尚未发送的内容只能标记为
“未计入最近确认值”。

## 压缩和恢复事件

达到阈值时会先发布：

```text
CONTEXT_COMPRESSION_REQUIRED
```

它表示“已经安排下一次模型调用前压缩”，并不表示压缩已经完成。关键字段包括：

- `reason=THRESHOLD_REACHED`；
- `nextCallBaselineTokens`；
- `thresholdRatio`；
- `compressionBeforeNextCall=true`。

实际压缩过程会发布：

```text
CONTEXT_COMPRESSION_STARTED
CONTEXT_COMPRESSION_COMPLETED
CONTEXT_COMPRESSION_FAILED
```

Usage 相关压缩完成后，`CONTEXT_COMPRESSION_COMPLETED` 还会包含 `usageStatus`、
`compressionRequired`、`compressionReason` 和 `usageBaselineValid`。正常情况下基准会变为
`BASELINE_INVALIDATED`，等待下一次成功模型调用重新确认。

完整事件注册和投递方式见 [AgentEventListener](./agent-event-listener)。

## 高级配置

### 输出预留的优先级

`reservedOutputTokens` 按以下优先级确定：

1. `AgentContextUsagePolicy.reservedOutputTokens`；
2. 当前 Turn 的请求级 `ChatOptions.maxTokens`；
3. Agent 默认 `ChatOptions.maxTokens`；
4. `ChatModelCapabilities.maxOutputTokens`。

策略中设置为 `0` 表示不显式覆盖，继续按后续来源回退。若总容量未知，或输出预留不小于总容量，框架不会
计算比例，也不会基于比例触发压缩。

如果不同请求允许的输出长度差异很大，可以不在 Usage 策略中固定预留，而是在 Turn 或 Agent 的
`ChatOptions.maxTokens` 中配置实际值。

### 阈值如何选择

`compressionThresholdRatio` 的合法范围为 `(0, 1]`，默认值为 `0.85`。

| 阈值 | 特点 | 适用情况 |
| --- | --- | --- |
| `0.70`～`0.80` | 压缩更早，安全余量大，摘要次数较多 | 新增工具结果可能很大 |
| `0.85` | 默认平衡值 | 大多数持续对话 |
| `0.90` | 更充分利用窗口，突发大输入风险更高 | 输入增长较稳定 |
| `1.00` | 只有确认基准达到可用容量才标记 | 不建议用于不可预测输入 |

阈值只依据最近确认基准，不会预估下一条用户输入。因此即使配置了 90%，一次很大的新输入仍可能直接触发
供应商的上下文超限异常；此时由异常恢复机制兜底。

### 上下文超限异常恢复

当模型抛出 `TokenLimitExceededException` 时，Runner 只对以下阶段执行压缩恢复：

- `INPUT_CONTEXT`：输入历史超过上限；
- `TOTAL_CONTEXT`：输入与输出合计超过总上下文。

以下阶段不会自动压缩：

- `OUTPUT`：通常应降低输出上限，压缩历史不一定能解决；
- `UNKNOWN`：框架不会猜测未知异常一定来自输入上下文。

恢复流程固定使用第一次选择的同一个模型，不会因为这次异常切换到另一个模型：

```text
调用模型 → 上下文超限 → 压缩较早历史 → 保存压缩状态 → 重建 Prompt → 同模型重试
```

每次重试都算一次真实模型调用和一次 iteration。`maxTokenLimitRecoveryAttempts(1)` 表示最多执行一次
“压缩后重试”；如果没有可压缩历史、压缩失败或达到次数上限，异常会进入正常的模型失败处理流程。

即使供应商从不返回 Usage，异常恢复仍然可以工作，但需要满足以下条件：

- `contextUsagePolicy.enabled=true`；
- `compressOnTokenLimitExceeded=true`；
- 配置带 `stateStore` 和 `compressor` 的持久化压缩策略；
- 模型适配器能把供应商错误分类为 `INPUT_CONTEXT` 或 `TOTAL_CONTEXT`。

### 工具调用循环

一个 Turn 可能经历“模型请求工具 → 执行工具 → 再次调用模型”。如果第一次模型响应返回的 Usage 已达到
阈值，第二次模型调用前就会执行压缩，不需要等用户发送下一条消息。

当前 Turn 的协议链不会被压缩；框架只处理可压缩的较早历史，并保护 `keepRecentTurns` 指定的最近完整轮次。

### 动态模型和 Usage 作用域

Usage 状态按以下维度隔离：

```text
conversationId + Agent ID + Agent version + modelKey
```

模型路由到不同模型时，不会把一个模型的容量和 Usage 基准错误复用到另一个模型。自定义模型应提供稳定的
`modelKey`；如果没有提供，框架会保守地使用模型实现类名作为回退。

## 状态存储

### 本地开发：内存 Usage Store

```java
AgentContextUsageStateStore usageStateStore =
    new InMemoryAgentContextUsageStateStore();
```

它适合单进程测试，应用重启后状态会丢失。Usage 策略还需要一个持久化压缩 Store；框架当前没有面向生产的
内存压缩 Store，测试场景可以实现 `AgentContextCompressionStateStore`，生产环境建议直接使用 JDBC 或 Redis。

### JDBC

```java
JdbcAgentStoreConfig storeConfig =
    JdbcAgentStoreConfig.builder(dataSource)
        .tablePrefix("app_agent_")
        .build();

storeConfig.schema().initialize();

AgentContextUsageStateStore usageStateStore =
    storeConfig.usageStateStore();
AgentContextCompressionStateStore compressionStateStore =
    storeConfig.compressionStateStore();
```

多个应用实例必须连接同一数据库。正式环境应使用 Flyway、Liquibase 等工具迁移表结构，而不是每次启动都
依赖 `schema().initialize()`。

### Redis

```java
RedisAgentStoreConfig storeConfig =
    RedisAgentStoreConfig.builder("redis://127.0.0.1:6379")
        .keyPrefix("my-app:agent:")
        .build();

AgentContextUsageStateStore usageStateStore =
    storeConfig.usageStateStore();
AgentContextCompressionStateStore compressionStateStore =
    storeConfig.compressionStateStore();
```

不同环境和租户应使用明确的 Key 前缀。应用关闭时应关闭由 URI 创建并持有客户端资源的 `storeConfig`。

### 自定义 Usage Store

自定义存储需要实现：

```java
public interface AgentContextUsageStateStore {
    AgentContextUsageState load(
        String conversationId,
        String usageScopeKey);

    boolean save(
        AgentContextUsageState state,
        long expectedVersion);
}
```

`save(...)` 必须实现 CAS 语义：只有当前版本等于 `expectedVersion` 时才保存。不能采用“先查询、再无条件更新”，
否则同一会话的并发请求可能互相覆盖。自定义 Compression Store 也应遵守对应接口的版本约束。

更多 Store 生命周期、Schema 和多实例建议见 [Agent Store](./store)。

## 自定义模型接入

自定义 `ChatModel` 要完整支持此功能，需要完成两件事。

### 1. 暴露模型容量

```java
@Override
public ChatModelCapabilities getCapabilities() {
    return new ChatModelCapabilities(
        "my-provider:my-model",
        128_000,
        8_192);
}
```

`modelKey` 应在同一模型和容量配置下保持稳定。`contextWindowTokens` 或 `maxOutputTokens` 未知时传 `0`，
不要填入猜测值。

### 2. 写入供应商 Usage

模型适配器应把供应商响应中的输入、输出和总 Token 写入返回的 `AiMessage`。如果供应商只返回总量，也可以
仅设置 `totalTokens`；如果不返回总量，则必须同时提供输入和输出，框架才能建立确认基准。

供应商的上下文超限错误还应转换为 `TokenLimitExceededException`，并准确设置 `Phase`。无法确定阶段时使用
`UNKNOWN`，不要为了触发恢复而错误标记为 `INPUT_CONTEXT`。

## 常见配置方案

### 只展示 Usage，不希望主动接近阈值

Usage 策略启用后必须具备可执行的持久化压缩策略。可以把阈值配置为 `1.0`，并关闭异常恢复：

```java
AgentContextUsagePolicy.builder()
    .enabled(true)
    .stateStore(usageStateStore)
    .compressionThresholdRatio(1.0d)
    .compressOnTokenLimitExceeded(false)
    .maxTokenLimitRecoveryAttempts(0)
    .build();
```

这仍可能在已确认基准达到可用容量时标记压缩。如果业务要求绝不压缩，应只读取模型原始 Usage，而不要启用
`AgentContextUsagePolicy`。

### 不展示比例，只处理上下文超限

可以不配置 `contextWindowTokens`，保留 Usage 策略和异常恢复。此时框架不会计算比例或按阈值压缩，但识别到
`INPUT_CONTEXT` 或 `TOTAL_CONTEXT` 异常后仍可压缩并重试。

### 同时使用本地增量策略和服务端 Usage

持久化压缩策略可以同时配置 `decider` 与 `tokenEstimator`。调用前，Runner 会先消费 Usage 阈值标记；没有
标记时，再执行原有自动压缩决策。无论由哪种机制真正改变模型视图，旧 Usage 基准都会失效。

## 常见问题

### 压缩后 Token 数据还准确吗？

压缩前保存的数字仍是历史上真实返回的 Usage，但已经不能代表压缩后的模型上下文，因此状态会变为
`BASELINE_INVALIDATED`，比例和剩余量不再输出。等下一次成功调用返回完整 Usage 后才重新准确。

### 能否实时估算用户正在输入的内容？

当前功能不支持。它只展示最近一次服务端确认值，不进行本地分词、字符比例或自适应校准。前端可以提示
“当前未发送内容尚未计入”。

### 供应商没有返回 Usage 怎么办？

不能可靠展示占用比例和剩余量。若供应商能返回明确的上下文超限错误，仍可以使用异常触发的压缩恢复。

### 为什么达到阈值后不立即压缩？

当前模型响应已经成功生成，没有必要在响应路径末尾增加摘要延迟。延迟到下一次真实调用前压缩，既不会拖慢
当前结果返回，也能保证下一次请求使用压缩后的视图。

### 为什么必须持久化压缩状态？

摘要和压缩游标决定了模型实际看到的历史。若只保存 Usage 标记，不保存压缩结果，应用重启或切换实例后会
出现视图不一致，也可能重复压缩同一段历史。

### 阈值已经低于 100%，为什么仍可能超限？

阈值只依据最近一次确认值。下一条很长的用户输入、大型工具结果或供应商隐藏的上下文开销都可能让请求直接
越过上限，因此仍建议开启异常恢复。

## 监控与排障

生产环境建议至少监控：

- `MODEL_COMPLETED` 中 `usageSource=UNAVAILABLE` 的比例；
- `baselineRatio` 的分布和达到阈值的会话数；
- `CONTEXT_COMPRESSION_REQUIRED` 到 `COMPLETED` 的成功率和耗时；
- `compressionReason` 中阈值触发与异常恢复的比例；
- Token 超限恢复次数、重试后成功率和无可压缩历史的次数；
- Usage Store 与 Compression Store 的 CAS 冲突和保存失败。

排障时可按以下顺序检查：

1. 模型配置是否包含真实的 `contextWindowTokens`；
2. `MODEL_COMPLETED` 的 `usageSource` 是否为 `PROVIDER_CONFIRMED`；
3. Agent 是否使用带 `conversationId` 的持续会话 API；
4. Usage 与 Compression Store 是否连接到同一环境；
5. 压缩策略是否有可压缩的较早历史，`keepRecentTurns` 是否设置过大；
6. 模型适配器是否正确分类 `TokenLimitExceededException.Phase`；
7. 压缩后 UI 是否仍错误使用失效的历史比例。

## 与其他上下文功能的关系

| 功能 | 解决的问题 | 数据来源 |
| --- | --- | --- |
| 历史轮数/消息数窗口 | 限制附加多少历史 | 本地消息结构 |
| `maxAttachedTokens(...)` | 按本地估算限制模型窗口 | 本地 Token 估算 |
| 自动增量压缩 | 按本地决策器定期生成摘要 | 本地 Decider 和估算器 |
| `AgentContextUsagePolicy` | 展示已确认用量并按阈值/异常触发压缩 | 供应商 Usage 和异常 |
| `AgentBudget` | 限制一个 Turn 的累计消耗和调用次数 | Turn 运行统计 |

这些能力可以组合使用，但应保持语义清晰：供应商确认值用于展示和审计，本地估算用于提前裁剪，Turn Budget
用于控制单项任务的总成本。

## 相关文档

- 配置持续会话和历史窗口：[上下文管理](./context-management)
- 了解压缩器、摘要设计和增量策略：[上下文压缩](./context-compression)
- 监听 Usage 与压缩事件：[AgentEventListener](./agent-event-listener)
- 配置 JDBC、Redis 和任务持久化：[Agent Store](./store)
- 了解模型失败处理与路由恢复：[模型故障恢复](./model-recovery)
- 配置模型请求参数：[ChatConfig](../chat/chat-config)

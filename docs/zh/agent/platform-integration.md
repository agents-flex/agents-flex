---
title: Agent 平台集成与扩展
description: 基于 Agent 运行时建设模式配置、版本管理、调用审计、效果报表、迭代推荐、兼容性校验和模拟演示平台。
---

# Agent 平台集成与扩展

<div v-pre>

## 框架与平台的职责

Agent 核心模块是执行数据面，负责可靠地推进一次任务。模式说明、配置页面、参数版本、报表和推荐规则属于平台控制面。

```text
平台控制面
├── 模式目录与说明
├── 参数 Schema 与配置版本
├── 发布、回滚和历史记录
├── 兼容性校验
├── 迭代规则与推荐
├── 审计查询和效果报表
└── 模拟器与时间线 UI
          |
          | 解析有效配置
          v
Agent 运行时
├── Agent + version
├── AgentExecutionMode
├── AgentExecutionPolicy
├── AgentContextPolicy
├── AgentRunOptions
├── AgentRunner
├── AgentRunStore
└── AgentRunEventStore
```

平台可以独立设计数据库和 API。核心要求是：任务启动前把平台配置解析成稳定的运行对象，任务启动后冻结影响执行语义的配置。

## 模式定义数据

平台可以维护自己的模式目录：

```java
public final class AgentModeDefinition {
    private String modeCode;
    private String name;
    private String description;
    private List<String> applicableScenarios;
    private Map<String, ParameterSchema> parameterSchemas;
    private boolean enabled;
    private long currentVersion;
}
```

例如，平台可以为默认工具调用模式记录以下定义：

```json
{
  "modeCode": "TOOL_CALLING",
  "name": "工具调用执行模式",
  "description": "模型决策、调用工具、读取结果并继续决策",
  "applicableScenarios": ["数据查询", "工具调用", "多步骤任务"],
  "enabled": true
}
```

模式目录由平台维护。创建 Agent 定义时，平台把 `TOOL_CALLING` 解析为对应的运行时实现：

```java
AgentExecutionMode mode = ToolCallingAgentExecutionMode.INSTANCE;
```

以后新增其他模式时，可以映射到自定义 `AgentExecutionMode`。

## Agent 定义版本

配置中心发布 Agent 时，应使用稳定逻辑 ID 和不可变版本：

```java
Agent agent = Agent.builder("order-agent")
    .id("order-agent")
    .version("17")
    .instructions(config.getInstructions())
    .chatModel(modelResolver.resolve(config.getModelId()))
    .tools(toolResolver.resolve(config.getToolIds()))
    .executionMode(modeResolver.resolve(config.getModeCode()))
    .executionPolicy(toExecutionPolicy(config))
    .contextPolicy(toContextPolicy(config))
    .attributes(config.getDisplayAttributes())
    .build();
```

`InMemoryAgentRegistry` 已支持同时注册同一 Agent ID 的多个版本：

```java
registry.register(agentVersion16);
registry.register(agentVersion17);

registry.resolveLatest("order-agent"); // 创建新任务时选择最新注册版本
registry.resolve("order-agent", "16"); // 恢复 Run 时精确选择定义版本
```

Checkpoint 保存 `agentId + agentVersion`。使用版本 16 创建的存量任务会继续按版本 16 恢复，新创建的任务可以使用版本 17。模型产生 ToolCall 时还会冻结 `AgentToolReference`；工具注册表根据 Agent 版本、工具名称和可选 binding 信息恢复实现，避免多个配置版本并发运行时互相覆盖。

::: warning 不要覆盖已发布版本
同一个 `agentId + version` 应视为不可变发布物。修改配置时创建新版本，不要原地改变模型、工具、模式或系统指令。
:::

## 模式参数映射

平台参数可以分成三类。

### 执行控制参数

映射到 `AgentExecutionPolicy`：

```java
AgentExecutionPolicy policy = AgentExecutionPolicy.builder()
    .maxIterations(config.getMaxIterations())
    .maxSteps(config.getMaxSteps())
    .toolErrorStrategy(config.getToolErrorStrategy())
    .retryPolicy(AgentRetryPolicy.builder()
        .maxRetries(config.getMaxRetries())
        .initialDelayMillis(config.getRetryInitialDelayMillis())
        .maxDelayMillis(config.getRetryMaxDelayMillis())
        .multiplier(config.getRetryMultiplier())
        .build())
    .budget(AgentBudget.builder()
        .maxDurationMillis(config.getMaxDurationMillis())
        .maxInputTokens(config.getMaxInputTokens())
        .maxOutputTokens(config.getMaxOutputTokens())
        .maxTotalTokens(config.getMaxTotalTokens())
        .maxToolCalls(config.getMaxToolCalls())
        .build())
    .build();
```

### 上下文和记忆参数

例如“最近保留 20 条消息”：

```java
AgentContextPolicy contextPolicy = AgentContextPolicy.recentMessages(20);
```

平台所说的“5 轮对话”需要先转换成消息窗口。工具调用一轮通常包含 AiMessage 和一个或多个 ToolMessage，不能简单假设一轮等于两条消息。复杂场景可以实现自定义 `AgentContextPolicy`，配置摘要、Token 窗口或协议感知裁剪。

上下文策略只影响发送给模型的消息，Checkpoint 仍保存完整历史。

### 模式专用参数

模式自己解释的参数可以保存在 Agent attributes 或模式实现对象中：

```java
Agent.builder("review-agent")
    .executionMode(new ReviewExecutionMode(reviewConfig))
    .attribute("reflectionRounds", 2)
    .attribute("qualityThreshold", 0.85)
    .build();
```

未知参数仅保存到 attributes 并不会自动影响 Runner。平台必须通过映射器把参数应用到执行策略、上下文策略或具体模式实现。

## 单次运行的动态推荐

Agent 定义提供默认值，平台可以根据任务类型和历史数据生成本次 Run 的覆盖策略：

```java
IterationRecommendation recommendation = recommendationService.recommend(
    taskType,
    taskComplexity,
    agent.getId(),
    agent.getVersion()
);

AgentExecutionPolicy effectivePolicy = AgentExecutionPolicy.builder()
    .maxIterations(recommendation.getIterations())
    .maxSteps(agent.getExecutionPolicy().getMaxSteps())
    .retryPolicy(agent.getExecutionPolicy().getRetryPolicy())
    .toolErrorStrategy(agent.getExecutionPolicy().getToolErrorStrategy())
    .budget(agent.getExecutionPolicy().getBudget())
    .build();

AgentRunOptions options = AgentRunOptions.builder()
    .executionPolicy(effectivePolicy)
    .metadata("taskType", taskType)
    .metadata("recommendedIterations", recommendation.getIterations())
    .metadata("recommendationReason", recommendation.getReason())
    .build();

AgentRun run = runner.start(agent, input, options);
```

有效策略随 Snapshot 保存，不会因为推荐规则更新而改变运行中的任务。

## 自定义运行模式

默认模式已经覆盖模型原生 ToolCall 循环。需要其他内部行为时实现 `AgentExecutionMode`：

```java
public final class ReflectionExecutionMode implements AgentExecutionMode {
    public String getId() { return "reflection"; }
    public String getVersion() { return "2"; }

    @Override
    public void validate(Agent agent) {
        Object rounds = agent.getAttributes().get("reflectionRounds");
        if (!(rounds instanceof Number)) {
            throw new IllegalStateException("reflectionRounds is required");
        }
    }

    @Override
    public AgentStepResult step(AgentExecutionContext context) {
        AgentRun run = context.getRun();
        if (!run.getModeState().containsKey("prepared")) {
            run.putModeState("prepared", true);
            return context.checkpointAndContinue();
        }
        return context.executeToolCallingStep();
    }
}
```

`modeState` 会随 Checkpoint 保存。模式必须只保存目标 Store 能够序列化的数据，不要保存模型、工具、线程或连接对象。

自定义模式用于封装单个 AgentRun 内部的决策策略。跨系统节点编排、并行分支和复杂条件由工作流模块承载，工作流可以通过 Runner API 启动、暂停和恢复 AgentRun。

## 兼容性校验

平台可以在发布前执行：

- 模式参数 Schema 校验；
- 模型是否支持 ToolCall；
- 模型温度、上下文窗口和输出限制校验；
- 工具名称和参数 Schema 校验；
- Tool metadata 可序列化、大小和敏感字段校验；
- `AgentToolReference` bindingId、bindingVersion 的可解析性校验；
- 审批策略是否覆盖高风险工具；
- 重试参数和预算校验；
- 上下文窗口是否能容纳工具协议消息；
- `AgentExecutionMode.validate(agent)`。

框架的 Builder 和模式 `validate()` 是最终防线，平台校验器负责生成面向管理员的完整报告。

## 调用审计

创建 Run 时原子写入平台维度：

```java
AgentRunOptions options = AgentRunOptions.builder()
    .metadata("accountId", accountId)
    .metadata("interface", request.getRequestURI())
    .metadata("module", "agent-management")
    .metadata("modeConfigVersion", modeConfigVersion)
    .build();
```

为所有事件添加查询字段：

```java
runner.addEventEnricher((run, eventType) -> {
    Map<String, String> attributes = new HashMap<>();
    attributes.put("accountId", String.valueOf(run.getMetadata().get("accountId")));
    attributes.put("module", String.valueOf(run.getMetadata().get("module")));
    attributes.put("taskType", String.valueOf(run.getMetadata().get("taskType")));
    return attributes;
});
```

框架事件自动包含：

```text
agentId、agentVersion
executionModeId、executionModeVersion
status
iteration、maxIterations、remainingIterations
stepCount、maxSteps
toolName、toolCallId、error
```

需要保存调用内容和结果时，使用 `AgentListener` 写专用审计表，并完成脱敏、长度限制和访问控制。

## 迭代时间线

平台按 sequence 读取事件：

```java
List<AgentRunEvent> events = eventStore.load(runId, afterSequence, 100);
```

可以渲染为：

```text
14:00:01 Run 开始
14:00:01 第 1 次模型调用，剩余 4 次
14:00:03 模型请求调用 query_order
14:00:03 query_order 执行成功
14:00:03 Checkpoint version 3
14:00:04 第 2 次模型调用，剩余 3 次
14:00:05 Run 完成
```

EventStore 保存索引友好的执行事实，Snapshot 保存完整消息状态。不要通过事件回放恢复 Run。

## 效果统计与报表

平台可以把终止事件和 Snapshot 投影到分析表：

```text
agent_id、agent_version、execution_mode_id、mode_config_version
task_type、status、iteration_count、tool_call_count、retry_count
input_tokens、output_tokens、duration_millis、completed_at
```

结合 OpenTelemetry 的模型和工具指标，可以生成：

- 按模式版本统计成功率；
- 按迭代次数统计任务完成率；
- 模型 P50、P95、P99 延迟；
- 工具错误率和平均耗时；
- 达到最大迭代次数的比例；
- 平均 Token 成本；
- 不同任务类型的推荐迭代区间。

报表查询不应直接扫描 Snapshot JSON，建议使用事件消费者构建独立分析投影。

## 模拟演示

模拟器可以复用同一套 Runner：

```java
AgentRunner simulationRunner = new AgentRunner(
    new InMemoryAgentRunStore(),
    simulationAgentRegistry,
    simulationToolRegistry,
    new InMemoryAgentRunEventStore()
);
```

模拟环境应使用测试模型或可控模型代理，将副作用工具替换为 Dry-run Tool，设置较小预算，并通过 EventStore 实时展示时间线。模拟结果必须明确标记，不能写入正式业务系统。

## 推荐的平台模块

```text
agent-mode-catalog       模式定义、参数 Schema、适用场景
agent-definition-service Agent 配置、版本、发布、回滚
agent-runtime-adapter    配置转换为 Agent 和 AgentRunOptions
agent-validation-service 模型、工具、模式兼容性报告
agent-audit-service      调用日志、迭代时间线、审批记录
agent-analytics-service  成功率、耗时、Token、迭代效果投影
agent-recommendation     根据任务类型和历史数据推荐参数
agent-simulator          Dry-run 工具、事件可视化、配置对比
```

这些模块依赖 Agent 核心契约，但不需要进入 `agents-flex-core`。

## 延伸阅读

- [架构与核心组件](./architecture.md)
- [Agent 与 AgentRun](./agent-and-run.md)
- [执行循环与生命周期](./execution-lifecycle.md)
- [事件、监听器与审计](./events.md)
- [生产实践](./production.md)

</div>

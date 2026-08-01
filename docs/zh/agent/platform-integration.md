---
title: 业务平台集成
---

# 业务平台集成

## 概述

Agent 运行时提供执行、恢复和扩展契约，不规定管理平台如何存储“模式定义、适用场景、推荐迭代次数、发布记录”等业务配置。平台可以自由设计数据库和管理 API，再通过 `AgentLoader` 将当前配置组装为可执行 Agent。

这种边界避免框架把产品限制为固定表结构，同时保证平台配置最终能落到模型、工具、策略、Middleware 和执行模式等运行能力上。

## 快速开发

假设平台分别保存 Agent 主表、模型绑定表、工具授权表和策略表，Loader 可以统一转换：

```java
public final class DatabaseAgentLoader implements AgentLoader {
    @Override
    public Agent loadActive(String agentId) {
        AgentConfig config = repository.findPublished(agentId);
        return assemble(config);
    }

    @Override
    public Agent load(String agentId, String version) {
        AgentConfig config = repository.findVersion(agentId, version);
        return assemble(config);
    }

    private Agent assemble(AgentConfig config) {
        return Agent.builder()
            .id(config.id())
            .version(config.version())
            .instructions(config.instructions())
            .chatModel(modelFactory.create(config.modelConfig()))
            .tools(toolFactory.load(config.toolBindings()))
            .executionPolicy(policyMapper.map(config.policy()))
            .build();
    }
}
```

新消息按当前已发布配置调用 `loadActive`；历史 Run 恢复时按 Snapshot 中的版本调用 `load`，确保旧状态仍由兼容定义解释。

## 平台可以保存什么

常见业务数据包括：

- Agent 名称、说明、适用任务类型和负责人；
- 模型供应商、模型参数与提示词模板；
- 可用工具、工具权限和审批规则；
- 最大迭代、重试、预算与上下文策略；
- 执行模式 ID、产品侧参数和发布版本；
- 不同任务类型的推荐策略及实验结果；
- 草稿、发布、回滚和变更历史。

这些字段不需要成为 Agent 核心 API 的固定属性。用于模型执行的内容映射到 `instructions`、策略或 `metadata`；纯展示、推荐理由和审批发布流程继续留在平台领域模型中。

## 配置校验

发布前应由平台完成两层校验。结构校验检查范围、必填项和引用关系，例如最大迭代必须大于零；运行校验组装一次 Agent，让执行模式的 `validate(...)`、工具 schema 和模型能力检查尽早失败。

平台还可以增加自身规则，例如某模型是否支持多模态、是否允许 ToolCall、工具返回结构是否符合组织规范，以及高风险工具是否配置审批。校验报告属于平台查询模型，框架不强制报告格式。

## 模式管理

平台若要展示“工具调用模式”“监督模式”等，可保存名称、定义、适用场景和可配置参数。执行时应把稳定的模式 ID 映射到受信任的 `AgentExecutionMode` 实现，再把经过校验的参数放入策略或模式可读取的配置。

不要把数据库中的 Java 类名直接反射实例化，也不要让用户配置绕过 `maxSteps` 和预算。模式说明可以自由迭代，运行模式 ID 与版本则应保持稳定，以支持 Checkpoint 恢复。

## 日志、时间线与报表

`AgentRunSnapshot` 适合查询当前状态、消耗、最终结果和下一次调度时间；`AgentRunEventStore` 适合构建迭代时间线、模型延迟、工具调用明细和异常轨迹。平台可以把事件同步到日志或分析系统，并关联自己的用户账号、接口、模块和租户字段。

成功率、平均迭代次数、Token 成本和延迟等指标应由事件与最终状态聚合，而不是写回 Agent 定义。对于不同迭代上限的效果比较，可由平台按 Agent 版本、任务类型和策略标签分组生成报告。

## 模拟演示

模拟器可以复用真实 Runner，但使用隔离的 Store、受限工具或只读沙箱，并订阅实时事件绘制模型调用、ToolCall、审批和结果时间线。演示环境仍应执行预算和权限检查，不能因为“模拟”而连接生产副作用工具。

## 多租户与权限

租户、用户和调用来源可放入 `AgentInvocationContext`，让 Middleware 与 Tool 在每次调用前鉴权；用于跨进程重建的稳定租户 ID 可写入 Run metadata。平台查询 Snapshot、Command、Event 和 Artifact 时必须再次实施数据权限，不能把不可猜测的 runId 当作授权机制。

Tool metadata 可描述分类、风险级别等静态属性，但最终权限决策应结合调用用户和当前参数动态完成。

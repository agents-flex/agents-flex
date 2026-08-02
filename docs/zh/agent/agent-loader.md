---
title: AgentLoader
description: 按稳定 ID 和版本加载 Agent，并把配置数据装配为可执行运行定义。
---

# AgentLoader

## 概述

`AgentLoader` 负责把持久化快照中的 `agentId + agentVersion` 重新解析为完整 `Agent`。快照不会序列化 `ChatModel`、Tool 或 Middleware，因此 Loader 是跨进程恢复和子 Agent 委派的必要组成部分。

## 接口

```java
public interface AgentLoader {
    Agent load(String agentId, String version);
    Agent loadActive(String agentId);
}
```

`load` 必须按精确版本加载历史定义；`loadActive` 返回当前生效版本，用于创建新子任务或平台入口。两者语义不应混淆。

## 内存实现

```java
AgentLoader loader = new InMemoryAgentLoader(root, analyst, tester);
AgentRunner runner = AgentRunner.builder()
    .agentLoader(loader)
    .build();
```

`InMemoryAgentLoader` 在构造时冻结集合。同一 ID 的最后一个 Agent 成为 active 版本，但所有传入版本仍可通过 `load(id, version)` 查找。它适合测试、Demo 和静态单进程应用。

## 自定义 Loader

```java
public final class DatabaseAgentLoader implements AgentLoader {
    private final AgentConfigRepository repository;
    private final ModelRegistry models;
    private final ToolRegistry tools;

    @Override
    public Agent load(String id, String version) {
        AgentConfig config = repository.find(id, version);
        return config == null ? null : assemble(config);
    }

    @Override
    public Agent loadActive(String id) {
        AgentConfig config = repository.findActive(id);
        return config == null ? null : assemble(config);
    }

    private Agent assemble(AgentConfig config) {
        return Agent.builder(config.name())
            .id(config.id())
            .version(config.version())
            .instructions(config.instructions())
            .chatModel(models.require(config.modelId()))
            .tools(tools.resolve(config.toolIds()))
            .build();
    }
}
```

配置存储可以拆成多张业务表，框架不限制结构；Loader 的输出必须是已经绑定模型、工具和策略的完整 Agent。

## 缓存与发布

Agent 不可变，适合按 `id:version` 缓存。active 指针则应有更短的缓存时间或由配置发布事件失效：

- 历史版本缓存可长期保留。
- 新建 Run 使用 active 版本。
- 已存在 Run 始终使用快照绑定版本。
- 下线版本前确认没有可恢复 Run 仍引用它。

## 规划与子 Agent

`AgentPlanningPolicy.allowAgent(...)` 只是委派白名单，不会自动注册目标 Agent。规划开始前，Runner 会用 Loader 加载允许的目标定义并向模型暴露其 ID、名称和描述。加载不到目标时，规划无法正确执行。

## 错误处理

建议区分以下问题并提供清晰诊断：

- Agent ID 不存在。
- 指定历史版本已删除。
- 模型或工具注册项缺失。
- 工具名称冲突。
- 执行模式 ID/版本与快照不匹配。

不要在 `load(id, oldVersion)` 失败时自动返回 active 版本，这会让待执行工具与原快照的语义漂移。

## 生产要求

Loader 应是线程安全的，且装配结果中的模型、工具与 Middleware 也应满足共享并发要求。配置若来自不可信输入，还应在发布阶段校验工具授权、预算上限、委派深度和模式白名单，而不是等到 Worker 恢复时才失败。

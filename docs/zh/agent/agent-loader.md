---
title: Agent 加载与版本
description: 使用 AgentLoader 按 ID 和版本查找 Agent，让新任务使用最新配置，让未完成任务继续使用原配置。
---

# Agent 加载与版本

## 概述

刚开始开发时，应用通常直接创建一个 `Agent`，然后交给 `AgentRunner` 执行。这种方式简单直接：

```java
Agent agent = Agent.builder("support-agent")
    .chatModel(chatModel)
    .build();

AgentTurn turn = runner.run(agent, "查询订单状态");
```

当应用开始长期运行后，Agent 的配置会不断变化。例如，客服 Agent 的第二个版本可能修改系统指令、增加
退款工具，或者调整工具参数。此时可能出现下面的情况：

1. 用户昨天发起退款任务，任务正在等待主管审批；
2. 今天应用发布了新版客服 Agent，退款工具的参数也发生了变化；
3. 主管今天批准后，昨天的任务需要继续执行。

如果昨天的任务直接使用今天的新配置，原来的工具参数可能无法识别，审批规则也可能已经不同。
`AgentLoader` 解决的就是这个问题：根据 Agent 的固定 ID 和版本号，找到任务当时使用的完整 Agent 配置。

它同时支持两种加载需求：

```text
通过 Agent ID 创建新任务 → 加载当前生效版本
恢复已有任务             → 加载任务创建时的原版本
```

因此，`AgentLoader` 可以理解为 Agent 配置的统一查找入口。它不负责运行任务，也不规定配置必须保存到
哪里；配置可以来自 Java 代码、数据库或配置中心。

## 何时需要使用

| 应用情况 | 是否需要专门配置 AgentLoader |
| --- | --- |
| 本地示例或单次调用，始终直接传入同一个 Agent | 通常不需要 |
| 任务可能等待审批、表单或自动重试 | 建议配置 |
| 应用重启后需要继续未完成任务 | 需要 |
| 多个服务实例或后台 Worker 共同执行任务 | 需要 |
| Agent 配置保存在数据库或配置中心 | 需要 |
| 希望只根据 Agent ID 创建新任务 | 需要 |
| 同一个 Agent 会同时保留多个版本 | 需要 |

如果应用不保存任务，也不存在后台执行和版本切换，可以先直接传入完整 `Agent`，无需一开始就实现数据库
Loader。

## 快速开始

`InMemoryAgentLoader` 是框架提供的内存实现，适合本地开发、测试和配置固定的单进程应用。

### 1. 为 Agent 设置稳定 ID 和版本

```java
Agent supportAgent = Agent.builder("客服助手")
    .id("support-agent")
    .version("1")
    .instructions("帮助用户查询订单并回答售后问题。")
    .chatModel(chatModel)
    .tool(queryOrderTool)
    .build();
```

| 配置 | 作用 |
| --- | --- |
| `id("support-agent")` | Agent 的固定标识，用于长期查找同一个 Agent |
| `version("1")` | 当前配置的版本，用于区分这个 Agent 的不同发布内容 |
| `instructions(...)` | 当前版本使用的系统指令 |
| `tool(...)` | 当前版本实际可以执行的工具 |

`id` 应保持稳定，例如始终使用 `support-agent`；发布新配置时修改 `version`，不要为同一个 Agent 随意
更换 ID。

### 2. 创建 Loader

```java
AgentLoader agentLoader =
    new InMemoryAgentLoader(supportAgent);
```

内存 Loader 会保存构造时传入的 Agent。它不会自动扫描 Spring Bean，也不会从数据库读取配置。

### 3. 配置 AgentRunner

```java
AgentRunner runner = AgentRunner.builder()
    .agentLoader(agentLoader)
    .build();
```

配置后，`AgentRunner` 在恢复任务时，就能根据任务中记录的 Agent ID 和版本找回对应配置。

创建任务时仍然可以直接传入 Agent：

```java
Agent activeAgent =
    agentLoader.loadActive("support-agent");

AgentTurn turn = runner.run(
    activeAgent,
    "查询订单 O-1001 的状态");
```

`loadActive(...)` 返回当前生效版本。只有一个版本时，它就是创建 Loader 时传入的 `supportAgent`。

## 管理多个版本

发布第二个版本时，Agent ID 保持不变，版本号和实际配置发生变化：

```java
Agent version1 = Agent.builder("客服助手")
    .id("support-agent")
    .version("1")
    .instructions("帮助用户查询订单状态。")
    .chatModel(chatModel)
    .tool(queryOrderV1Tool)
    .build();

Agent version2 = Agent.builder("客服助手")
    .id("support-agent")
    .version("2")
    .instructions("帮助用户处理订单和售后问题。")
    .chatModel(chatModel)
    .tool(queryOrderV2Tool)
    .tool(createTicketTool)
    .build();

AgentLoader agentLoader =
    new InMemoryAgentLoader(version1, version2);
```

对于相同的 Agent ID，`InMemoryAgentLoader` 将最后传入的 Agent 作为当前生效版本，同时保留前面传入的
历史版本：

```java
Agent active =
    agentLoader.loadActive("support-agent"); // version 2

Agent oldVersion =
    agentLoader.load("support-agent", "1");  // version 1
```

两种加载方式不能混用：

| 方法 | 使用时机 | 示例结果 |
| --- | --- | --- |
| `loadActive(agentId)` | 通过 Agent ID 创建新任务 | 返回当前生效的 version 2 |
| `load(agentId, version)` | 恢复已有任务 | 精确返回任务原来的 version 1 |

框架恢复任务时会自动使用精确版本，不需要业务代码先调用 `load(...)`。上面的代码主要用于说明两个方法
的区别。

`InMemoryAgentLoader` 创建后不能继续添加或修改 Agent。发布新版本时，需要用新的 Agent 列表创建新的
Loader。它适合配置随应用一起发布的场景，不适合需要在后台随时编辑 Agent 的平台。

## 新任务与旧任务如何选择版本

假设当前生效版本已经从 version 1 升级到 version 2，并且新任务通过 Agent ID 创建：

```text
新任务
  └─ 使用 version 2

升级前创建、尚未完成的任务
  └─ 继续使用 version 1
```

旧任务继续使用原版本，是为了保证它看到的系统指令、工具名称、工具参数和审批规则与创建时一致。不要在
恢复旧任务失败时自动改用当前版本，否则可能执行错误的工具或产生不符合原审批条件的操作。

如果调用 `runner.run(agent, ...)` 时直接传入了完整 Agent，Runner 会使用这个明确传入的版本，不会再用
Loader 将它替换为当前生效版本。

以下变化通常应发布一个新版本：

- 删除或重命名工具；
- 修改工具参数或返回结果格式；
- 改变付款、退款、发布等工具的业务含义；
- 修改会影响任务继续执行的审批规则或 Middleware；
- 大幅调整系统指令，导致同一个任务可能产生不同处理结果。

仅修改与执行无关的展示名称或说明时，是否升级版本可以由业务发布规范决定。关键判断是：这个变化会不会
影响已经创建但尚未完成的任务。

## 根据 Agent ID 创建任务

接入会话记录后，可以只传入 Agent ID，让 Runner 自动加载当前生效版本：

```java
AgentTurn turn = runner.run(
    "support-agent",
    conversationId,
    "继续查询刚才的订单");
```

这里的 `conversationId` 用来找到同一段对话的历史消息，因此还需要为 Runner 配置
`ChatMemoryProvider`。如果应用不需要连续对话，可以继续使用快速开始中“先调用 `loadActive(...)`，再将
完整 Agent 传给 `runner.run(...)`”的方式。会话配置见[上下文管理](./context-management)。

## 从数据库或配置中心加载

当 Agent 可以在管理后台编辑和发布时，通常需要实现自己的 `AgentLoader`：

```java
public final class DatabaseAgentLoader
    implements AgentLoader {

    private final AgentConfigRepository repository;
    private final ModelRegistry models;
    private final ToolRegistry tools;

    @Override
    public Agent load(String agentId, String version) {
        AgentConfig config =
            repository.find(agentId, version);

        return config == null
            ? null
            : assemble(config);
    }

    @Override
    public Agent loadActive(String agentId) {
        AgentConfig config =
            repository.findActive(agentId);

        return config == null
            ? null
            : assemble(config);
    }

    private Agent assemble(AgentConfig config) {
        return Agent.builder(config.name())
            .id(config.id())
            .version(config.version())
            .instructions(config.instructions())
            .chatModel(
                models.require(config.modelId()))
            .tools(
                tools.resolve(config.toolIds()))
            .build();
    }
}
```

示例中的 `AgentConfigRepository`、`ModelRegistry`、`ToolRegistry` 和 `AgentConfig` 都代表业务系统自己的
组件，不是框架内置类：

| 组件 | 负责内容 |
| --- | --- |
| `AgentConfigRepository` | 从数据库读取 Agent 的版本配置和当前生效版本 |
| `ModelRegistry` | 根据模型 ID 找到应用已经创建好的模型客户端 |
| `ToolRegistry` | 根据工具 ID 找到应用中可以执行的工具对象 |
| `assemble(...)` | 将配置数据、模型和工具组合成完整的 `Agent` |

数据库通常只需保存 ID、版本、系统指令、模型 ID、工具 ID 和发布状态等配置数据。模型客户端、数据库连接
和工具函数仍由应用创建，再由 Loader 组装到 Agent 中。

如果 Agent 还使用执行策略、审批策略或 Middleware，也应在 `assemble(...)` 中装配。Loader 返回的必须
是可以直接执行的完整 Agent，而不是只包含名称和指令的半成品。

## 推荐的发布流程

一个简单、可靠的版本发布过程如下：

1. 创建新的版本号，不覆盖已经发布的历史版本；
2. 检查模型配置、工具名称、工具参数和权限规则是否完整；
3. 使用测试请求验证新版本可以正常执行；
4. 将新版本标记为当前生效版本；
5. 新任务开始使用新版本，旧任务仍可加载历史版本；
6. 确认没有未完成任务引用旧版本后，再按照保留策略归档。

不要直接修改已经发布版本的实际内容。相同的 `agentId + version` 应始终表示相同的执行配置，否则任务
恢复前后的行为会发生变化。

## 缓存建议

数据库 Loader 可以缓存已经组装好的 Agent，减少重复查询和对象创建：

| 缓存内容 | 建议 |
| --- | --- |
| 指定版本，如 `support-agent:1` | 已发布版本不再修改，可以较长时间缓存 |
| 当前生效版本 | 可能随发布切换，应使用较短缓存或在发布后主动清除 |

Agent 会被多个任务复用，因此 Loader 和它返回的模型、工具及 Middleware 都需要支持并发调用。不要把某个
用户或某次任务的数据保存在这些共享对象的成员变量中。

## 常见错误

### 找不到 Agent ID

确认调用方使用的 ID 与 `Agent.id(...)` 完全一致。`loadActive(...)` 找不到配置时可以返回 `null`，Runner
会将其作为明确的加载失败处理。

### 找不到历史版本

这通常表示旧版本被过早删除，或者不同服务实例使用了不同的配置数据。不要自动返回当前版本作为替代；
应恢复历史版本或明确终止相关旧任务。

### Agent 可以加载，但运行时找不到工具

确认该版本配置中记录的工具 ID 可以由 `ToolRegistry` 解析，并且工具名称与任务创建时保持一致。加载成功
不代表 Agent 一定完整，发布前仍需验证。

### 多个服务实例加载结果不同

多实例部署时，各实例应读取同一套已发布配置和当前生效版本。发布过程中还要确保所有实例都已经具备新
版本需要的模型、工具和 Middleware 实现，再将新版本设为生效状态。

## 使用建议

1. 为每个 Agent 设置稳定、明确的 ID，并为每次有执行影响的发布创建新版本。
2. 保留仍可能被未完成任务引用的历史版本。
3. 恢复任务时严格加载原版本，不要静默回退到当前版本。
4. 发布前验证模型、工具、审批规则、执行限制和 Middleware 配置。
5. 对 Loader 的找不到配置、装配失败和缓存刷新情况记录监控与告警。

## 相关文档

- 创建和配置 Agent：[Agent 配置](./agent)
- 了解任务状态如何保存：[任务快照](./snapshot)
- 配置任务状态存储：[任务快照持久化](./store)
- 配置后台恢复与执行：[后台任务 Worker](./worker)
- 配置连续对话：[上下文管理](./context-management)

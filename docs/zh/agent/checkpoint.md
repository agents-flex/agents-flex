---
title: Checkpoint 与持久化 Store
---

# Checkpoint 与持久化 Store

## 概述

Agent 调用模型、等待审批、执行工具或调度子 Agent 时，进程随时可能退出。Checkpoint 将一个 `AgentRun` 在稳定边界上的可恢复状态保存为 `AgentRunSnapshot`，让后续请求或其他 Worker 可以从该边界继续，而不是重新执行整个任务。

`AgentRunSnapshot` 保存消息、待执行 ToolCall、运行状态、计数器、预算消耗、计划、暂停原因、模式状态、父子关系和版本等数据。它不保存 `ChatModel`、`Tool`、Middleware 或业务服务对象；恢复时由 `AgentLoader` 按 `agentId + agentVersion` 重新组装 Agent。

## 快速开发

单进程开发可直接使用内存 Store：

```java
AgentRunStore runStore = new InMemoryAgentRunStore();

AgentRunner runner = AgentRunner.builder()
    .runStore(runStore)
    .agentLoader(agentLoader)
    .build();

AgentRun run = runner.start(agent, new UserMessage("生成月度报告"));
runner.runUntilBlocked(run);

// 可在另一个 Runner 实例中恢复。
AgentRun restored = runner.restore(run.getId());
runner.runUntilBlocked(restored);
```

`start(...)` 会创建初始 Checkpoint，执行循环会在模型响应、工具结果、暂停、重试和终止等稳定边界继续保存。应用不需要直接构造 `AgentRunSnapshot`。

## 稳定边界

Checkpoint 表示“已经完成且可安全重放之后状态”的边界。例如模型返回一个 ToolCall 后，框架先保存待处理调用；工具执行成功后，再保存 ToolMessage 和调用记录。进程在两者之间退出时，恢复逻辑仍能识别当前处于哪一步。

`AgentToolInvocation` 还为工具提供稳定的 invocation ID。支付、发券、创建工单等有副作用的工具，应把该 ID 作为业务幂等键，避免进程在工具成功但 Checkpoint 尚未提交时造成重复副作用。

## 乐观锁与版本

`AgentRunStore.save(snapshot, expectedVersion)` 使用比较并交换语义。首次写入的期望版本为 `-1`，后续保存必须携带调用方看到的版本；若另一个线程或 Worker 已经更新同一 Run，则抛出 `AgentRunVersionConflictException`。

版本冲突不是应该被覆盖的普通异常。它说明当前执行者持有过期状态，应停止推进并重新加载最新 Checkpoint。

## JDBC Store

```java
JdbcAgentStoreConfig stores = JdbcAgentStoreConfig.builder(dataSource)
    .tablePrefix("af_agent_")
    .binaryColumnType("BLOB")
    .build();

stores.schema().createIfNotExists();

AgentRunner runner = AgentRunner.builder()
    .runStore(stores.runStore())
    .commandStore(stores.commandStore())
    .eventStore(stores.eventStore())
    .artifactStore(stores.artifactStore())
    .agentLoader(agentLoader)
    .build();
```

不同数据库的二进制列类型不同，例如 PostgreSQL 常用 `BYTEA`。生产环境通常由 Flyway 或 Liquibase 管理表结构，`createIfNotExists()` 更适合本地开发和测试。

## Redis Store

```java
try (RedisAgentStoreConfig stores = RedisAgentStoreConfig
        .builder("redis://127.0.0.1:6379")
        .keyPrefix("myapp:agent:")
        .build()) {
    AgentRunner runner = AgentRunner.builder()
        .runStore(stores.runStore())
        .commandStore(stores.commandStore())
        .eventStore(stores.eventStore())
        .artifactStore(stores.artifactStore())
        .agentLoader(agentLoader)
        .build();
}
```

同一部署环境的 API 服务与 Worker 必须使用相同键前缀。多个租户是否共享前缀应由应用的数据隔离策略决定。

## 序列化

JDBC 和 Redis 默认使用 `FastjsonAgentStoreSerializer`，以 fastjson2 JSONB 保存多态消息和不可变值对象。默认白名单只恢复 Agents-Flex 与常用 JDK 类型。如果 `metadata` 或 `modeState` 中确实需要业务值对象，应显式增加尽可能精确的类型前缀：

```java
AgentStoreSerializer serializer =
    new FastjsonAgentStoreSerializer("com.example.agent.state.");
```

持久化字段更推荐字符串、数字、布尔值、列表和 Map。调用上下文中的用户身份、数据库连接或业务 Service 不应进入 Snapshot，而应在 Worker 恢复时通过 `AgentInvocationContextProvider` 重新附加。

## Store 的职责边界

四类 Store 解决不同问题：

| Store | 保存内容 | 主要用途 |
| --- | --- | --- |
| `AgentRunStore` | 最新 Snapshot、Lease、取消标记 | 恢复和调度 |
| `AgentRunCommandStore` | 待消费恢复命令 | 跨进程审批、补充输入和唤醒 |
| `AgentRunEventStore` | 追加式运行事件 | 审计、时间线和指标 |
| `AgentArtifactStore` | 被卸载的大型内容 | 控制 Snapshot 和模型上下文大小 |

Snapshot 是最新状态，不是完整审计日志；事件是过程记录，不应被用来代替恢复所需的最新状态。


---
title: Store 持久化
description: 实现 Turn Store，并满足版本、租约和原子状态转换契约。
---

# Store 持久化

## 概述

AgentRunner 使用 Turn Store 保存当前状态与 Worker Lease。内存实现仅适合测试和单实例试用。

## Store 责任矩阵

| Store | 核心键 | 必须保证 |
| --- | --- | --- |
| `AgentTurnStore` | turnId | 乐观版本、原子取消、Lease fencing、可运行领取 |

## 配置生产 Store

根据存储类型选择一个实现模块。版本应与 `agents-flex-agent` 保持一致：

```xml
<!-- JDBC 与 Redis 二选一；同时使用时也可以都引入。 -->
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-agent-store-jdbc</artifactId>
    <version>${agents-flex.version}</version>
</dependency>

<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-agent-store-redis</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

### JDBC

应用提供已经配置连接池的 `DataSource`。开发环境可以幂等初始化 Schema；生产环境通常应把相同 DDL
纳入数据库迁移工具，而不是让每个实例在启动时执行：

```java
JdbcAgentStoreConfig storeConfig = JdbcAgentStoreConfig.builder(dataSource)
    .tablePrefix("af_agent_")
    // PostgreSQL 可使用 BYTEA；不同数据库应选择对应的二进制列类型。
    .binaryColumnType("BLOB")
    .build();

storeConfig.schema().initialize();
AgentTurnStore turnStore = storeConfig.turnStore();
```

### Redis

可以传入 Redis URI，也可以传入应用共享的 `JedisPooled`。使用 URI 创建客户端时，应用关闭阶段应关闭
配置对象：

```java
RedisAgentStoreConfig storeConfig = RedisAgentStoreConfig
    .builder("redis://127.0.0.1:6379")
    .keyPrefix("agents-flex:agent:")
    .build();

AgentTurnStore turnStore = storeConfig.turnStore();
// 应用关闭时调用 storeConfig.close();
```

配置完成后，将同一个 Store 交给应用级 Runner：

```java
AgentRunner runner = AgentRunner.builder()
    .turnStore(turnStore)
    .agentLoader(databaseAgentLoader)
    .build();
```

多实例部署必须共享 Turn Store 和 AgentLoader。事件持久化不属于 Framework Store，由业务系统通过 `AgentEventListener` 接入自己的审计库、消息平台或 Outbox。

业务需要统一对话时间线时，可另外配置共享的持久化 `ChatMemory`。它保存消息与审批页面状态，不替代
Turn Store；Runner 始终先提交 Snapshot，再幂等投影 ChatMemory，投影失败会在后续保存或恢复时补偿。

从旧版本升级时，原 JDBC events/event_sequences、artifacts、commands 表以及对应 Redis 键不再被 Framework 读写。Schema 初始化不会自动删除历史数据；应由业务确认归档或迁移完成后自行清理。

`AgentRunStore` 到 `AgentTurnStore` 的迁移同样不会自动执行。JDBC 实现使用新的 `turns` 表、`turn_id`、
`next_runnable_at` 列；Redis 实现使用 `turn`、`turns` 和 `runnable-turns` key。
应用必须在停机迁移窗口完成旧数据转换，或者先让旧 Run 全部进入终态后再切换新 Store。

## AgentTurnStore 契约

实现需要支持：保存/加载快照、请求取消、领取 runnable Turn、续租/释放 Lease，以及查询等待父 Turn 的终态子任务。关键规则：

- `save` 比较 expectedVersion 并原子写入新版本。
- 带有效 Lease 的写入必须验证 `workerId + leaseId`。
- `claimRunnable` 原子选择并加租，不能让两个 Worker 同时领取。
- 新领取生成唯一 `leaseId`；同名 Worker 的旧租约不能写入。
- 取消标记是单调的，不能被旧快照清除。
- Runnable 包括 READY、可推进 RUNNING、到期 RETRY，以及未终止但被取消的 Turn。

数据库实现通常使用事务与条件 UPDATE：`WHERE turn_id=? AND version=?`，再检查影响行数。

## 序列化与 Schema

可使用 `FastjsonAgentStoreSerializer` 将 Snapshot 编码为 JSONB，也可实现跨语言格式。无论格式如何，都应记录 schema/version，进行向后兼容测试，并限制多态类型白名单。

## 事务边界

Framework 只保证 Turn Snapshot 的原子状态转换。外部审批、用户输入需要与业务数据可靠一致时，应在业务系统中使用事务 Inbox/Outbox、幂等事件 ID 和补偿扫描，再调用 `submitResume`。

## 清理策略

- 先确认 Turn 终态且不再被父任务引用。
- 清理作业使用明确批次，避免大事务和全表锁。

## 验证自定义实现

至少测试并发版本冲突、Turn Lease 失效、同名 Worker fencing、取消与保存竞态、重试到期领取和序列化重启往返。

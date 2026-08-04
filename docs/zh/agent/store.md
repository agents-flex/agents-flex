---
title: Store 持久化
description: 实现 Run、Command 与 Artifact Store，并满足版本、租约、幂等和事务契约。
---

# Store 持久化

## 概述

AgentRunner 必需 Run Store 和 Command Store：前者保存当前状态与 Worker Lease，后者保存外部恢复命令。Artifact Store 只在配置可选 `ToolResultOffloader` 时使用，用于保存从 Prompt 外置的大型内容。内存实现仅适合测试和单实例试用。

## Store 责任矩阵

| Store | 核心键 | 必须保证 |
| --- | --- | --- |
| `AgentRunStore` | runId | 乐观版本、原子取消、Lease fencing、可运行领取 |
| `AgentRunCommandStore` | commandId | 幂等提交、命令租约、确认/释放/失败 |
| `AgentArtifactStore` | artifactId | 内容完整性、租户隔离、持久读取 |

## 配置生产 Store

```java
AgentRunner runner = AgentRunner.builder()
    .runStore(jdbcRunStore)
    .commandStore(jdbcCommandStore)
    .toolResultOffloader(ToolResultOffloader.largerThan(
        20_000, objectStorageArtifactStore))
    .agentLoader(databaseAgentLoader)
    .build();
```

多实例部署至少必须共享 Run Store、Command Store 和 AgentLoader。启用结果外置时，所有 Runner 还必须使用指向同一持久化 Artifact Store 的等价 `ToolResultOffloader` 配置。事件持久化不属于 Framework Store，由业务系统通过 `AgentEventListener` 接入自己的审计库、消息平台或 Outbox。

从旧版本升级时，原 JDBC events/event_sequences 表和 Redis 事件键不再被 Framework 读写。Schema 初始化不会自动删除历史数据；应由业务确认归档或迁移完成后自行清理。

## AgentRunStore 契约

实现需要支持：保存/加载快照、请求取消、领取 runnable Run、续租/释放 Lease，以及查询等待父 Run 的终态子任务。关键规则：

- `save` 比较 expectedVersion 并原子写入新版本。
- 带有效 Lease 的写入必须验证 `workerId + leaseId`。
- `claimRunnable` 原子选择并加租，不能让两个 Worker 同时领取。
- 新领取生成唯一 `leaseId`；同名 Worker 的旧租约不能写入。
- 取消标记是单调的，不能被旧快照清除。
- Runnable 包括 READY、可推进 RUNNING、到期 RETRY，以及未终止但被取消的 Run。

数据库实现通常使用事务与条件 UPDATE：`WHERE run_id=? AND version=?`，再检查影响行数。

## Command Store 契约

`submit` 对相同 commandId 和相同内容返回已有记录；同 ID 不同内容应拒绝。`claim` 原子领取 PENDING 或租约过期的 CLAIMED 命令并增加 attempts。只有当前租约持有者可以 acknowledge、release 或 fail。

Run Snapshot 更新与 Command 确认若不能放在同一事务中，必须依靠 processed-command 标记与幂等恢复实现最终一致性。

## Artifact Store 契约

`save` 返回包含 ID、runId、mediaType、size、checksum 和 metadata 的引用。生产实现应验证 load 内容 checksum，并按 tenant/run 授权。对象存储适合大内容，数据库适合较小且强事务关联的数据。

## 序列化与 Schema

可使用 `FastjsonAgentStoreSerializer` 将 Snapshot 与 Command 编码为 JSONB，也可实现跨语言格式。无论格式如何，都应记录 schema/version，进行向后兼容测试，并限制多态类型白名单。

## 事务边界

Run Snapshot 和 Command 确认可能跨两个写入步骤，框架通过 processed-command 标记与幂等恢复缩小故障窗口。业务事件需要与业务数据可靠一致时，应在业务系统中使用事务 Outbox、幂等事件 ID 和补偿扫描。

## 清理策略

- 先确认 Run 终态且不再被父任务引用。
- Command 的保留期应覆盖外部回调重放窗口。
- Artifact 生命周期不得短于引用它的 Snapshot。
- 清理作业使用明确批次，避免大事务和全表锁。

## 验证自定义实现

至少测试并发版本冲突、重复命令、命令租约过期、Run Lease 失效、同名 Worker fencing、取消与保存竞态、重试到期领取、父子恢复扫描和序列化重启往返。

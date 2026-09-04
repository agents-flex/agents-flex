---
title: Agent Store 压测对比
description: 基于真实 Redis 和 MySQL 的 AgentTurnStore 性能对比、结果分析与选型建议。
---

# Agent Store 压测对比

## 概述

本文比较 `AgentTurnStore` 的 Redis 与 JDBC（MySQL）实现在创建 Turn、并发领取并完成 Turn，以及读取 Snapshot 三类负载下的表现。测试连接真实的本地 Redis 和 MySQL，不使用 Mock 或内存替代实现。

这组数据用于回答“在相同代码和本机环境下，两种 Store 的性能特征有什么不同”，不能直接代表生产环境容量。网络拓扑、数据体积、持久化策略、主从复制和硬件都会改变绝对数值。

## 先看结论

- Redis 的创建吞吐量约为 MySQL 的 **1.81 倍**，更适合 Turn 创建频繁的负载。
- 两者的完整领取与完成吞吐量接近，Redis 约高 **6%**；Redis 的 p99 为 68.560 ms，明显低于 MySQL 的 101.203 ms，尾延迟更稳定。
- Redis 的读取吞吐量约高 **18%**。两者读取 p99 几乎相同，MySQL 的 p95 略低，Redis 的 p50 更低。
- 对调度频率和尾延迟敏感时，优先考虑 Redis；重视关系型事务、现有数据库运维体系和审计查询时，MySQL 仍是合理选择。

## 测试对象

压测覆盖以下三个操作阶段：

| 阶段 | 操作 | 代表的实际负载 |
| --- | --- | --- |
| Create | 保存一个新的 `READY` Turn Snapshot | Agent 创建或开始执行 |
| Claim + complete | 并发领取 runnable Turn，保存 `COMPLETED` 状态并释放 Lease | Worker 调度、CAS 更新和租约处理 |
| Load | 按 `turnId` 并发加载 Snapshot | 状态查询、恢复和调度检查 |

其中 `Claim + complete` 不是一次简单写入。它覆盖候选扫描、原子领取、Lease 建立、带版本保存和 Lease 释放，更接近 Store 的核心调度路径。

## 测试环境与方法

| 项目 | 配置 |
| --- | --- |
| Redis | 本地 Docker 单节点，`127.0.0.1:6379` |
| MySQL | 本地 MySQL 5.7.44，`127.0.0.1:3306` |
| 生命周期记录数 | 2,000 |
| 并发读取数 | 10,000 |
| Worker 线程数 | 8 |
| MySQL 连接池 | HikariCP，最大连接数 10 |
| 数据内容 | 最小化序列化 Snapshot |
| 运行隔离 | 每个后端在独立 Maven JVM 中运行 |

创建阶段串行写入 2,000 条数据；生命周期和读取阶段使用 8 个线程并发执行。吞吐量按整个阶段的完成数除以耗时计算，p50、p95 和 p99 来自单次操作延迟样本。

每次运行使用唯一的 Redis Key Prefix 或 MySQL Table Prefix，结束后自动清理。此次验证中 2,000 个 Turn 均被且仅被处理一次，没有出现版本冲突、重复领取或记录丢失；压测结束后 Redis 残留键和 MySQL 残留压测表均为 0。

## 压测结果

| 后端 | 操作 | 吞吐量 | p50 | p95 | p99 |
| --- | --- | ---: | ---: | ---: | ---: |
| Redis | Create | 261.8 ops/s | 2.938 ms | 5.792 ms | 7.833 ms |
| MySQL | Create | 144.4 ops/s | 5.473 ms | 10.284 ms | 16.898 ms |
| Redis | Claim + complete | 198.5 ops/s | 38.201 ms | 57.858 ms | 68.560 ms |
| MySQL | Claim + complete | 187.8 ops/s | 38.376 ms | 71.790 ms | 101.203 ms |
| Redis | Load | 3,235.9 ops/s | 1.958 ms | 4.716 ms | 7.778 ms |
| MySQL | Load | 2,750.2 ops/s | 2.601 ms | 4.435 ms | 7.620 ms |

## 从结果看实现差异

### 创建：Redis 优势最明显

Redis 创建吞吐量比 MySQL 高约 81%，p50、p95 和 p99 也都更低。新的 Turn 需要保存 Snapshot 并维护 runnable 索引；在本地单节点环境下，Redis 的数据结构和命令路径在这个阶段更轻量。

如果业务会在短时间内集中创建大量 Agent Turn，这一差异更容易反映到入口响应时间和待调度数据的堆积速度上。

### 生命周期：平均吞吐接近，尾延迟不同

Redis 和 MySQL 的 `Claim + complete` 吞吐量分别为 198.5 ops/s 和 187.8 ops/s，差距只有约 6%。仅看平均吞吐，两种实现都能有效利用 8 个 Worker。

差异主要出现在尾部：两者 p50 几乎相同，但 MySQL p99 上升到 101.203 ms，Redis 为 68.560 ms。调度循环通常会连续执行领取和状态保存，因此尾延迟会影响 Worker 空转时间、任务启动抖动，以及高峰期积压的消化速度。对响应稳定性敏感时，p95/p99 比平均吞吐更值得关注。

### 读取：差距较小，不能只看单一分位数

Redis 读取吞吐量约高 18%，p50 更低；MySQL p95 略低，两者 p99 基本一致。这说明本次本地测试中的点查并不是决定性差距。实际系统中，网络距离、MySQL Buffer Pool 命中率、Redis 内存压力，以及 Snapshot 大小都可能改变结果。

## 如何选择

| 场景 | 建议 | 原因 |
| --- | --- | --- |
| 高频创建和调度、关注 p99 | Redis | 创建更快，生命周期尾延迟更低 |
| 多 Worker、希望降低调度抖动 | Redis | 本次测试中领取与完成路径更稳定 |
| 已有成熟 MySQL 高可用和备份体系 | JDBC + MySQL | 可以复用事务、备份、权限和运维能力 |
| 需要用 SQL 做审计、关联查询或离线分析 | JDBC + MySQL | 关系型数据更容易接入现有查询链路 |
| 规模尚未确定 | 用生产数据再压测 | 本地最小 Snapshot 结果不足以估算线上容量 |

Store 选型不应只比较 ops/s。还需要同时评估数据持久性目标、故障恢复、集群部署成本、监控能力，以及团队已有的运维经验。

## 测试边界

本次结果存在以下边界：

- Redis 和 MySQL 都是本地单节点，没有外部网络延迟。
- Snapshot 为最小化测试数据，没有覆盖大型消息上下文、压缩状态或复杂业务对象。
- 没有比较 Redis AOF/RDB、MySQL `innodb_flush_log_at_trx_commit` 等持久化参数。
- 没有覆盖 Redis Cluster、哨兵、MySQL 主从复制或跨可用区部署。
- 测试不是长时间稳态压测，没有衡量 GC、连接重建、数据增长和资源饱和后的变化。
- 测试验证了压测路径无重复领取和数据丢失，但不能替代完整的并发契约与故障注入测试。

因此，这些数字适合做同机相对比较，不应作为生产容量承诺。上线前应使用接近真实大小的 Snapshot、生产网络和持久化配置重复测试，并逐步增加 Worker 数直到吞吐不再线性增长。

## 复现测试

基准类位于对应 Store 模块的测试源码中，类名不匹配默认 Surefire 扫描规则，需要通过 `-Dtest` 显式运行。

Redis：

```bash
mvn -pl agents-flex-agent-store/agents-flex-agent-store-redis -am \
  -Dtest=RedisAgentTurnStoreBenchmark \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dredis.test.uri=redis://127.0.0.1:6379 \
  -Dstore.benchmark.tasks=2000 \
  -Dstore.benchmark.workers=8 \
  -Dstore.benchmark.reads=10000 test
```

MySQL 数据库需要预先创建。密码通过环境变量传入，避免写入 Maven 参数和本文；仍应按本机 Shell 的安全策略处理历史记录：

```bash
export MYSQL_TEST_PASSWORD='<password>'
mvn -pl agents-flex-agent-store/agents-flex-agent-store-jdbc -am \
  -Dtest=MysqlAgentTurnStoreBenchmark \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dmysql.test.url='jdbc:mysql://127.0.0.1:3306/agents_flex_jdbc_it?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC' \
  -Dmysql.test.user=root \
  -Dstore.benchmark.tasks=2000 \
  -Dstore.benchmark.workers=8 \
  -Dstore.benchmark.reads=10000 test
```

为减少 JVM 预热、GC 和资源竞争带来的交叉影响，应分别运行 Redis 与 MySQL 命令，并重复多轮观察中位结果。

## 结论

在本次真实本地 Store 压测中，Redis 在 Agent Turn 创建、整体读取吞吐和生命周期尾延迟方面占优，适合高频调度和延迟敏感场景。MySQL 的生命周期吞吐与 Redis 接近，读取尾延迟也没有明显劣势；当事务持久化、SQL 查询和既有数据库治理更重要时，JDBC 实现仍然具有实际价值。

最终选择应以业务的主要瓶颈为准：创建与调度延迟优先看 Redis，数据治理与关系型集成优先看 MySQL，并在生产等价环境中用真实 Snapshot 再次验证。

配置和 Store 契约详见 [任务快照持久化](./store)。

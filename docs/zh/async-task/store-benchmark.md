---
title: Async Store 压测对比
description: 基于真实 Redis 和 MySQL 的 AsyncTaskStore 性能对比、结果分析与选型建议。
---

# Async Store 压测对比

## 概述

本文比较 `AsyncTaskStore` 的 Redis 与 JDBC（MySQL）实现在创建任务、并发领取并完成任务，以及读取任务三类负载下的表现。测试直接连接真实的本地 Redis 和 MySQL，完整经过序列化、索引、CAS 和 Lease 路径。

Async Store 同时承担状态持久化与调度协调，因此不能只看最高吞吐量。典型延迟决定日常响应速度，p95/p99 尾延迟会影响 Worker 调度抖动，而数据库的一致性、可恢复性和运维方式同样影响最终选型。

## 先看结论

- Redis 创建吞吐量约为 MySQL 的 **1.23 倍**，且各延迟分位均更低。
- MySQL 的完整领取与完成吞吐量约比 Redis 高 **18%**，但 Redis 的延迟明显更低：p50 低约 41%，p99 低约 51%。这是“总吞吐”和“单任务延迟”指向不同选择的典型场景。
- 两者读取能力接近，Redis 吞吐量约高 **5%**；MySQL 的 p95/p99 略低，Redis 的 p50 更低。
- 低延迟、高频调度优先考虑 Redis；偏重批量生命周期吞吐、关系型事务和现有数据库治理，并能接受更高尾延迟时，可以选择 MySQL。

## 测试对象

压测覆盖 Async Task 的三个主要 Store 操作：

| 阶段 | 操作 | 代表的实际负载 |
| --- | --- | --- |
| Create | 创建一个到期可查询的 `RUNNING` 任务 | 提交任务并进入持久化调度 |
| Claim + complete | 并发领取到期任务，保存 `SUCCEEDED` 状态并释放 Lease | Worker 查询调度、CAS 更新和租约处理 |
| Load | 按任务 ID 并发加载 | 状态轮询、结果查询和恢复 |

`Claim + complete` 覆盖候选扫描、原子领取、Lease 建立、带版本保存和 Lease 释放。它比单条 UPDATE 更接近 Async Task Worker 的核心工作循环。

## 测试环境与方法

| 项目 | 配置 |
| --- | --- |
| Redis | 本地 Docker 单节点，`127.0.0.1:6379` |
| MySQL | 本地 MySQL 5.7.44，`127.0.0.1:3306` |
| 生命周期任务数 | 2,000 |
| 并发读取数 | 10,000 |
| Worker 线程数 | 8 |
| MySQL 连接池 | HikariCP，最大连接数 10 |
| 数据内容 | 最小化序列化 Async Task |
| 运行隔离 | 每个后端在独立 Maven JVM 中运行 |

创建阶段串行写入 2,000 个任务；生命周期和读取阶段使用 8 个线程并发执行。吞吐量按阶段整体耗时计算，p50、p95 和 p99 来自单次操作的延迟样本。

每轮压测使用唯一的 Redis Key Prefix 或 MySQL Table Prefix，并在结束后自动删除数据。本次验证没有出现 CAS 错误、重复领取或任务丢失；2,000 个任务全部进入终态，压测结束后 Redis 残留键和 MySQL 残留压测表均为 0。

## 压测结果

| 后端 | 操作 | 吞吐量 | p50 | p95 | p99 |
| --- | --- | ---: | ---: | ---: | ---: |
| Redis | Create | 340.3 ops/s | 1.886 ms | 5.019 ms | 7.245 ms |
| MySQL | Create | 275.7 ops/s | 2.798 ms | 5.682 ms | 12.184 ms |
| Redis | Claim + complete | 133.2 ops/s | 26.635 ms | 51.965 ms | 65.502 ms |
| MySQL | Claim + complete | 157.2 ops/s | 45.024 ms | 84.019 ms | 134.753 ms |
| Redis | Load | 2,238.6 ops/s | 2.663 ms | 8.010 ms | 13.920 ms |
| MySQL | Load | 2,126.6 ops/s | 3.094 ms | 7.560 ms | 12.895 ms |

## 从结果看实现差异

### 创建：Redis 吞吐和延迟同时占优

Redis 创建吞吐量约高 23%，p50 从 MySQL 的 2.798 ms 降至 1.886 ms，p99 从 12.184 ms 降至 7.245 ms。对于突发提交或需要快速持久化大量异步任务的入口，这一差异有助于减少创建阶段的排队。

### 生命周期：吞吐量与延迟需要分开看

MySQL 在本轮 `Claim + complete` 中完成 157.2 ops/s，比 Redis 的 133.2 ops/s 高约 18%。如果目标是让固定数量的 Worker 在单位时间内完成更多批量状态转换，MySQL 的这一结果值得关注。

但单任务延迟呈现相反结论：Redis p50 为 26.635 ms，MySQL 为 45.024 ms；Redis p99 为 65.502 ms，MySQL 达到 134.753 ms。Redis 的 p99 约低 51%，说明它在本次并发调度下响应更稳定。

吞吐量与延迟并不矛盾。领取操作按批次返回任务，线程并发、批次摊销、事务和连接池等待都会影响阶段总吞吐与单任务样本。需要快速唤醒单个任务时应优先看延迟；关注长时间批处理能力时则应结合吞吐量、积压增长率和资源利用率判断。

### 读取：总体接近

Redis 读取吞吐量约高 5%，p50 略低；MySQL p95 和 p99 略低。这个差异小于创建和生命周期阶段，不足以单独决定选型。实际轮询系统还应评估热点任务、缓存命中、任务 payload 大小和网络延迟。

## 如何选择

| 场景 | 建议 | 原因 |
| --- | --- | --- |
| 高频提交、快速查询调度、关注 p99 | Redis | 创建和生命周期延迟更低 |
| 单个任务需要尽快被 Worker 推进 | Redis | 本次测试的领取与完成延迟更稳定 |
| 长时间批处理，优先阶段总吞吐 | 先验证 MySQL | 本轮生命周期吞吐更高，但需确认尾延迟可接受 |
| 已有成熟 MySQL 高可用、备份和审计体系 | JDBC + MySQL | 可复用关系型数据库治理能力 |
| 需要 SQL 关联任务与业务数据 | JDBC + MySQL | 查询和数据集成更直接 |
| 真实任务包含较大请求或结果 | 重新压测 | 最小化 payload 无法代表序列化和网络成本 |

选择 Store 时还应考虑供应商调用时长、轮询周期、Lease 配置、任务积压峰值和故障恢复目标。供应商 HTTP 请求往往比 Store 操作慢得多，此时稳定性和可运维性可能比几%的 Store 吞吐差异更重要。

## 测试边界

本次压测有以下限制：

- Redis 和 MySQL 都运行在本机单节点环境，没有外部网络延迟。
- 使用最小化任务对象，没有覆盖大型请求参数、结果对象和业务 DTO。
- 没有比较 Redis AOF/RDB、MySQL 刷盘等级等持久化策略。
- 没有覆盖 Redis Cluster、哨兵、MySQL 主从复制或跨可用区网络。
- 没有模拟供应商 HTTP 调用、准入策略拒绝、延迟提交和多类查询任务混合负载。
- 测试时间较短，不代表数据持续增长、GC、连接波动和资源饱和后的稳态性能。
- 压测路径验证了无重复领取和任务丢失，但不能替代取消竞态、Lease 过期、故障恢复等契约测试。

因此，本文数字只用于同机、同参数下的相对比较。生产部署应使用真实任务数据和网络，逐步增加 Worker、任务量和运行时间，同时监控 CPU、内存、连接池、Redis 延迟和 MySQL 锁等待。

## 复现测试

基准类位于对应 Store 模块的测试源码中，类名不匹配默认 Surefire 扫描规则，需要通过 `-Dtest` 显式运行。

Redis：

```bash
mvn -pl agents-flex-async-task-store/agents-flex-async-task-store-redis -am \
  -Dtest=RedisAsyncTaskStoreBenchmark \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dredis.test.uri=redis://127.0.0.1:6379 \
  -Dstore.benchmark.tasks=2000 \
  -Dstore.benchmark.workers=8 \
  -Dstore.benchmark.reads=10000 test
```

MySQL 数据库需要预先创建。密码通过环境变量传入：

```bash
export MYSQL_TEST_PASSWORD='<password>'
mvn -pl agents-flex-async-task-store/agents-flex-async-task-store-jdbc -am \
  -Dtest=MysqlAsyncTaskStoreBenchmark \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dmysql.test.url='jdbc:mysql://127.0.0.1:3306/agents_flex_jdbc_it?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC' \
  -Dmysql.test.user=root \
  -Dstore.benchmark.tasks=2000 \
  -Dstore.benchmark.workers=8 \
  -Dstore.benchmark.reads=10000 test
```

Redis 与 MySQL 应分别运行，并重复多轮观察中位结果，避免 JVM 预热、GC 和两个后端争用本机资源影响结论。

## 结论

本次真实 Store 压测没有得出“一个后端全面胜出”的结论。Redis 在任务创建和生命周期延迟上更好，尤其将生命周期 p99 控制在 MySQL 的约一半；MySQL 则在这轮生命周期阶段取得约 18% 的吞吐优势。读取性能基本接近。

对延迟敏感、调度频繁的 Async Task 系统，Redis 更符合本次结果体现出的性能特征。对于强调批量吞吐、事务持久化、SQL 查询和现有数据库运维集成的系统，MySQL 仍然合适，但应确认更高的 p95/p99 不会放大任务调度抖动。最终决策应在生产等价环境中，用真实 payload 和目标并发再次验证。

配置、CAS 和 Lease 契约详见 [Store 持久化](./store)。

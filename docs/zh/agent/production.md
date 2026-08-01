---
title: 生产实践
---

# Agent 生产实践

## 概述

生产 Agent 不只是一次模型请求。它还涉及状态一致性、重复副作用、租约接管、成本上限、数据权限和版本恢复。Agents-Flex 提供这些机制的运行时边界，应用仍需根据自身基础设施和业务风险完成配置。

## 快速开发

上线前至少应从内存组件切换为共享 Store，并显式配置加载器、策略和事件：

```java
AgentRunner runner = AgentRunner.builder()
    .agentLoader(agentLoader)
    .runStore(stores.runStore())
    .commandStore(stores.commandStore())
    .eventStore(stores.eventStore())
    .artifactStore(stores.artifactStore())
    .middlewares(middlewares)
    .build();
```

API 节点负责创建、查询、取消和提交恢复命令；Worker 节点负责领取与执行。小规模部署可以让两种角色位于同一应用，但仍应通过 Store 协作。

## Agent 版本

每次可能影响恢复语义的配置发布都应生成稳定版本。`loadActive` 服务新消息，`load(id, version)` 服务历史 Run。滚动发布期间，新旧进程必须都能加载尚未结束 Run 所引用的版本。

删除旧模型或工具绑定前，应确认没有等待审批、计划子任务或重试中的 Run 仍引用它。无法继续恢复时，应由平台提供明确的迁移或人工终止流程，不能静默换成最新定义。

## Store 与一致性

Run、Command、Event 和 Artifact Store 应具有与任务价值匹配的持久性和备份策略。JDBC 适合依赖事务和关系查询的系统，Redis 适合低延迟调度；无论选择哪种实现，都需要监控容量、延迟和失败率。

数据库/Redis 服务端时间、Snapshot 乐观锁、Lease fencing 和父子 Run 原子创建都是分布式正确性的组成部分。不要在外层捕获版本冲突后直接覆盖最新状态。

## 工具安全与幂等

有副作用的工具必须：

- 使用 `AgentToolInvocation` 的稳定 ID 做业务幂等；
- 在执行前根据租户、用户、参数和 Tool metadata 鉴权；
- 为外部请求设置连接与读取超时；
- 对高风险操作配置结构化审批；
- 避免在日志和 ToolMessage 中泄漏凭据或敏感原文。

审批只表示允许执行当前 ToolCall，不代替工具自身权限检查。工具参数在审批后若发生变化，应视为新的调用并重新决策。

## 预算与容量

为每个 Run 配置迭代、step、时间、Token 和工具调用上限，并在平台侧设置不可突破的组织上限。Worker 批大小和轮询间隔应结合模型限流、工具容量及数据库写入能力调整。

长时间等待人工审批的任务需要更长时间预算，但不应长期占用线程或 Lease。`WAITING_*` 与 `RETRY_SCHEDULED` 状态都应该释放执行资源。

## 上下文与数据治理

开启上下文压缩和大型工具结果卸载，避免 Snapshot、模型请求和事件日志无界增长。Artifact Store 中的数据同样需要租户隔离、访问审计、保留期限和删除机制。

`AgentInvocationContext` 适合进程内身份和服务对象，不会自动持久化；跨进程只保存可安全公开给 Worker 的稳定标识，再从受信任系统重建权限上下文。

## 可观测性

实时事件用于当前节点的 UI 推送与低延迟 tracing，持久化事件用于审计和跨进程查询。建议至少监控：

- 各终止状态数量和成功率；
- 模型与工具延迟、错误率和重试次数；
- Token、工具调用量和预算超限；
- 等待审批、等待子 Agent 和重试队列时长；
- Lease 丢失、版本冲突和 Command 积压；
- Artifact 容量与读取失败。

事件增强器可加入租户、业务请求号和部署信息，但应控制标签基数并脱敏。

## 故障演练

上线前应验证 Worker 在模型调用前后、工具执行前后、父子 Run 创建后和审批命令消费时被终止的行为。还应测试 Lease 到期接管、重复命令、事件写入失败、旧版本 Agent 加载失败和外部工具超时。

这些测试的目标不是保证每一步只发生一次，而是保证状态可恢复、重复执行有幂等保护、错误可观察且最终不会由两个 Worker 静默覆盖。

## 上线检查

- 所有可恢复版本都能由 `AgentLoader` 加载；
- API 与 Worker 使用相同 Store 命名空间和序列化配置；
- 副作用工具具备业务幂等键和权限校验；
- 审批、重试、预算、取消和上下文策略有明确默认值；
- 事件、指标、告警和敏感信息脱敏已验证；
- Worker 优雅关闭、Lease 接管和数据备份经过演练；
- Snapshot metadata 与 modeState 中没有不可序列化对象或短期凭据。

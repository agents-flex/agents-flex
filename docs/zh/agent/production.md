---
title: 生产实践与故障边界
description: Agent 生产部署中的存储、幂等、事务、预算、权限、Lease、版本稳定性和监控建议。
---

# 生产实践与故障边界

<div v-pre>

## 不要直接使用内存 Store

以下实现主要用于测试和单进程开发：

- `InMemoryAgentRunStore`；
- `InMemoryAgentRunEventStore`；
- `InMemoryAgentRunCommandStore`；
- `InMemoryAgentArtifactStore`；
- `InMemoryAgentTaskStore`；
- `InMemoryAgentRegistry`；
- `InMemoryAgentToolRegistry`。

多实例生产环境至少需要共享 RunStore 和 CommandStore。需要任务计划、审计和大型结果外置时，再提供
共享 TaskStore、EventStore 和 ArtifactStore。核心模块当前只定义生产扩展契约，不内置 JDBC 或 Redis
实现，部署平台可以按现有基础设施选择数据库、消息系统和对象存储。

## 副作用幂等

工具审批、Checkpoint 和 Lease 都不能替代业务幂等。建议把以下字段之一传给外部写操作：

- ToolCall ID；
- AgentRun ID + ToolCall ID；
- 业务请求 ID；
- 业务对象 ID + 操作类型 + 版本。

外部服务应保存幂等键和第一次执行结果，重复请求返回相同结果。

AgentRunner 发起的工具调用可以通过 `AgentToolInvocation.current()` 取得默认幂等键、Run ID、根 Run ID、ToolCall ID 和冻结的 Tool Reference。业务工具不要从可变参数或模型生成文本中重新推导调用身份。

## Store 事务

生产 Store 的关键原子操作：

| 操作 | 原子要求 |
| --- | --- |
| Checkpoint | version 条件更新和新 Snapshot 同时提交 |
| 创建子 Run | 父 WAITING 与子 INSERT 同一事务 |
| Worker 领取 | runnable 判断、Lease 和 version 同时提交 |
| Lease 续租 | 校验 owner 后更新 |
| Task Plan | plan version 条件更新 |
| Command submit | commandId 唯一约束和命令正文原子写入 |
| Command claim | 状态判断、命令 Lease 和 attempts 原子更新 |
| Command apply | Run processed marker 与恢复状态同一 Checkpoint |

RunStore 和 TaskStore 分库时，需要 Saga、Outbox 或幂等修复任务处理跨 Store 崩溃窗口。

## Agent 与 Tool 版本稳定性

长期任务可能在代码升级后恢复。请保持：

- `agentId + agentVersion` 可继续解析；
- 同一个已发布版本保持不可变；
- Snapshot 中的 executionMode ID 和版本仍可解析；
- `AgentToolReference` 的 bindingId 和 bindingVersion 可继续解析；
- Tool 名称、参数和 Reference metadata 在已发布版本内保持不变；
- 已下线的 Agent 定义保留到相关 Run 全部进入终止状态。

平台发布新配置时注册新的 Agent version。存量 Run 恢复时继续绑定创建时的配置版本。单次运行的推荐迭代和预算应通过 `AgentRunOptions` 冻结到 Snapshot。

Tool metadata 会复制到 Reference 并进入 Snapshot。生产 Store 应限制 metadata 大小并校验可序列化类型；Registry 不应把凭据写入 Reference，而应通过 bindingId 在安全配置源中获取当前进程所需的客户端和认证信息。

## 权限与审批

`ToolApprovalPolicy` 是执行前决策，不是完整权限系统。结构化决定中的 code、reason 和 metadata 用于展示
和审计，不能替代授权结果。生产环境还应在 Agent Middleware、Tool 或 ToolInterceptor 中校验：

- 当前租户；
- 当前用户；
- 资源所有权；
- 操作范围；
- 审批人身份和审批时效；
- correlationId 与业务对象是否匹配。

审批记录建议包含审批人、时间、原因、ToolCall 摘要和最终执行结果。

## 预算

```java
AgentBudget.builder()
    .maxDurationMillis(5 * 60_000)
    .maxInputTokens(50_000)
    .maxOutputTokens(20_000)
    .maxTotalTokens(70_000)
    .maxToolCalls(30)
    .build();
```

注意：

- 时间从 Run 创建时计算，包括等待时间；
- Token 在模型响应后累计，因此超限响应已经产生费用；
- Token 超限后不会继续执行该响应中的副作用工具；
- Task Plan 中每个子 Run 使用各自 Agent 预算，根 Run 也有独立预算；
- 业务还应设置整个计划级成本限制。

## 多层重试

可能同时存在：

1. HTTP Client 网络重试；
2. ChatModel 请求重试；
3. AgentRun 持久化重试；
4. 业务外部服务自己的重试。

计算最坏尝试次数，避免指数放大。副作用工具通常只使用 AgentRun 级重试，并依赖幂等键。

## Lease 与超时

- 单次模型或工具超时应小于 Lease；
- 长步骤期间定期续租；
- 使用数据库时间判断过期；
- Worker ID 应包含实例和线程标识；
- 监控 Lease 过期接管次数；
- Worker 关闭时停止领取并尽量释放 Lease。

当前 Lease 续租会更新 Snapshot 版本，因此不要让独立心跳线程在同一个内存 `AgentRun` 正在提交 Checkpoint 时无协调地续租，否则会制造版本竞争。生产 Store 可以把 Lease/Fencing Token 与业务 Snapshot 版本拆分，或者由 Worker 在步骤边界串行续租。无论采用哪种方式，单次外部调用仍应短于 Lease 或具备底层超时。

## 持久化取消

控制面使用 `runner.requestCancellation(runId)`，不要依赖某个进程中的 `AgentRun` 对象。生产 Store 对取消标记必须采用单调写入语义：一旦为 true，普通 Checkpoint 不能覆盖回 false；`claimRunnable` 还应允许领取尚未终止的已取消等待任务。

协作式取消不会强杀模型 HTTP 请求或正在执行的 Tool。需要低取消延迟时，应同时配置客户端超时、可中断请求或外部任务取消 API。

## 事件安全

默认只在事件 attributes 中保存必要字符串。不要记录：

- API Key、Token 和 Cookie；
- 完整用户隐私数据；
- 未脱敏工具参数；
- 大段模型上下文；
- 数据库密码和连接串。

`AgentRuntimeEvent` 的 data 可以携带运行时对象，因此转发到 WebSocket、消息队列或日志前必须做字段
白名单和脱敏。模型 reasoning delta 是否允许保存，应由产品合规策略明确决定。

## 上下文与 Artifact

- 摘要器应有独立超时、Token 预算和敏感信息策略；
- 压缩后立即保存 Checkpoint，保证跨进程看到相同摘要；
- Artifact Store 应按租户和 Run 做授权，artifactId 不应可枚举；
- ToolMessage 只保存引用，不在日志中展开完整 Artifact；
- 配置内容保留和删除策略，避免 Run 已删除但大型结果永久残留；
- 模型需要完整结果时使用分页检索工具，不自动把 Artifact 全量装回 Prompt。

## 监控指标建议

| 指标 | 说明 |
| --- | --- |
| Run 创建、完成、失败数量 | 总体成功率 |
| Run 端到端耗时 | 包含等待与执行 |
| WAITING 状态数量与时长 | 审批和用户响应积压 |
| retryCount 分布 | 瞬时故障情况 |
| Worker claim 数与空轮询数 | 调度效率 |
| Lease 过期接管数 | Worker 稳定性 |
| Token 与 ToolCall 用量 | 成本和异常循环 |
| version conflict 数 | 并发执行或重复回调 |
| 任务计划完成率 | 复杂任务质量 |
| Command pending/failed 数与等待时长 | 外部审批和事件消费积压 |
| Context compaction 次数与压缩比例 | 上下文容量和摘要成本 |
| Artifact 数量、大小和读取失败率 | 大型结果存储健康度 |
| Runtime Event 消费延迟和丢弃数 | 实时 UI 与追踪健康度 |

## 测试建议

不要只测试单功能 happy path。至少覆盖：

- 审批 + Checkpoint + 跨 Runner 恢复；
- 工具失败 + retry + Worker；
- retry 等待 + 时间预算；
- Token 超限 + 副作用工具执行次数为 0；
- Lease 到期前后两个 Worker 竞争；
- 子 Agent 完成 + 父 Run 幂等恢复；
- 计划任务审批 + resume + 后续任务继续；
- Event sequence + 分页续读 + 跨 Runner 读取。
- Invocation Context 并发隔离 + 恢复后重新附加；
- Middleware 洋葱顺序 + 转换 + 短路 + 异常传播；
- 流式 ToolCall + 审批 + Command Inbox + Worker + Artifact 外置；
- Command 重复提交 + Checkpoint 后崩溃重投 + 多 Worker 竞争；
- 上下文压缩保留 ToolCall/ToolMessage 协议边界；
- Artifact Store 保存失败时 pending ToolCall 和 Checkpoint 不被破坏。

## 发布检查清单

1. 所有 Agent ID 和 Tool 名称稳定；
2. 副作用 Tool 具备幂等键；
3. Store 使用 CAS 和事务；
4. Worker Lease 大于正常单步耗时；
5. 模型、Tool 和计划均配置预算；
6. 审批接口校验用户权限和 correlationId；
7. 事件和日志完成脱敏；
8. 已发布的 Agent、模式和工具版本在存量 Run 结束前保持可解析；
9. 有积压、失败和 Lease 接管告警；
10. 完成崩溃恢复与重复回调演练。
11. Command Store、Artifact Store 和实时事件消费者完成容量与故障演练；
12. Invocation Context 中没有被误写入 Snapshot 的连接、凭据和服务对象。

</div>

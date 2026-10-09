# 故障恢复

## 概述

长期入图跨越模型调用、图数据库、实体注册表和文档状态存储。这些系统通常不能加入同一个数据库事务，因此可能出现：

~~~text
图写入成功
  -> 进程退出
  -> 文档状态尚未提交
~~~

如果恢复时重新调用模型，非确定性输出还可能生成另一份 Mutation。可靠恢复必须保存首次执行计划和当前阶段，再按原计划继续。

## 三种控制机制

### 稳定 operationId

标识同一次业务意图。相同操作恢复必须使用相同 operationId，不同计划不能复用同一个操作号。

### 文档 revision CAS

确保计划基于的文档版本没有被其他任务推进。即使两个任务都成功抽取，只有符合 expected revision 的状态提交才能成功。

### 文档级锁

减少同一 `Space + documentId` 的并发执行。它用于降低冲突，不替代幂等和 CAS。

三者解决的问题不同，生产系统通常需要同时使用。

## 操作状态机

`GraphIngestionOperation` 使用：

~~~text
PREPARED
  -> GRAPH_APPLIED
  -> STATE_COMMITTED
  -> COMPLETED

PREPARED -> FAILED -> PREPARED
~~~

| 阶段 | 含义 |
| --- | --- |
| `PREPARED` | 操作和原始计划已持久化，尚未确认图写入 |
| `GRAPH_APPLIED` | GraphWriter 已报告成功 |
| `STATE_COMMITTED` | 文档状态已经提交 |
| `COMPLETED` | 所有收尾阶段完成 |
| `FAILED` | 图写入确认前失败，可以按原计划重试 |

操作记录不是分布式事务，而是让故障位置可识别、让后续步骤可继续。

## planFingerprint

操作存储同时保存覆盖图路由、Mutation、文档状态、事实来源和实体注册内容的稳定计划指纹。

同一个 operationId 如果对应不同 planFingerprint，服务必须拒绝执行。即使它们基于相同文档 revision，也不能让一次模型重跑产生的新计划冒充旧操作恢复。

## OperationStore 的生产要求

`GraphIngestionOperationStore` 的实现应：

- 以 operationId 建立全局唯一索引；
- 在同一个存储事务中创建操作记录与原始计划；
- 原子实现阶段 `compareAndSet`；
- 完整序列化和恢复 GraphOptions、Mutation、状态与来源；
- 按更新时间稳定扫描未完成操作；
- 保留失败原因、更新时间和尝试次数等运维信息；
- 防止同一个恢复任务被多个实例同时认领。

接口要求实现 `createIfAbsent(operation, plan)`，原子保存操作记录和首次执行计划，不提供只保存操作状态的重载或降级实现。

实现还必须通过 `isRecoverySupported()` 明确声明是否支持 `getPlan(operationId)` 和
`listRecoverableOperations(limit)`。声明不支持时，服务仍可执行调用方持有的原计划，但会拒绝
`resume` 和恢复扫描，避免把“配置了操作存储”误判为“支持恢复”。

这个能力声明表示恢复 API 可用，并不保证跨进程持久化。内存实现返回 `true`，只能在当前进程内恢复；
生产环境需要数据库等可靠存储保存计划和阶段。

## 启动恢复

应用自己的任务系统可以在启动或周期调度时扫描：

~~~java
if (!ingestion.isRecoverySupported()) {
    throw new IllegalStateException("当前操作存储不支持恢复");
}
for (GraphIngestionOperation operation :
        ingestion.listRecoverableOperations(100)) {
    ingestion.resume(
        operation.getOperationId(),
        graphStore.writer());
}
~~~

`resume` 读取持久化的原始计划，不重新调用大模型。调度频率、租约、退避、最大尝试次数、死信和告警由开发者的任务系统负责。

## 各故障窗口如何处理

### PREPARED 之前失败

没有持久化执行意图，不应假设可以自动恢复。上层任务仍需保存原始文档和请求，再重新生成计划。

### PREPARED 后、写图前失败

按持久化计划重新调用 Writer。节点和边必须具有稳定身份，使重试收敛。

### 图写入返回成功、阶段推进前退出

操作仍可能停留在 PREPARED，而图已生效。这是最难的未知窗口。恢复可能再次执行 Mutation，因此计划中的 Upsert 和删除必须可重复；还应进行图状态对账。

### GRAPH_APPLIED 后退出

恢复会跳过 GraphWriter，继续实体注册和文档状态提交。

### 状态提交后退出

恢复继续推进操作阶段，不重新抽取或重复写图。

这说明 operationId 本身不是数据库级自动去重键。当前通用 Neo4j/Nebula Writer 不会保存它；真正的保护来自持久化阶段、稳定图身份和可重放计划。

## 乐观锁冲突

`GraphDocumentStateStore.compareAndSet` 使用 expected revision。若另一任务先提交新版本，当前计划不能覆盖最新状态。

发生冲突时不应强制把旧计划写入状态表。需要：

1. 查询最新文档状态；
2. 判断当前 operationId 是否已经提交；
3. 若是新竞争版本，废弃旧计划；
4. 基于最新版本重新规划；
5. 对已经产生的图副作用进行对账。

## 本地锁与分布式锁

`LocalGraphIngestionLockProvider` 只在当前 JVM 内按文档串行，不阻塞不同文档。多实例部署必须注入共享实现，例如数据库租约、Redis 锁或确定性任务分区。

共享锁应考虑：

- 获取超时；
- 租约到期和续约；
- 持有者身份；
- 仅持有者可释放；
- 进程暂停和网络分区；
- 锁失效后仍依赖 CAS 阻止旧任务提交。

锁永远不能替代状态存储唯一约束和 revision CAS。

## 不同文档的并发

不同文档可以并行，但可能同时：

- 注册同一个新实体；
- 更新同一个图节点属性；
- 支持或撤销同一个 EdgeKey；
- 争用模型和数据库配额。

因此，Entity Registry 需要唯一约束，图属性冲突需要业务合并策略，关系引用判断需要并发安全。模型客户端、自定义 Resolver 和所有存储实现也必须线程安全。

## 重试原则

- 只对可恢复错误重试；
- 使用原 operationId 和原计划；
- 采用有上限的指数退避；
- 未知提交结果先核验；
- 不在失败对象上继续执行，重新进入恢复入口；
- 不重新调用模型替换原计划；
- 把永久 Schema、权限和协议错误送人工处理；
- 记录每次尝试和最终处置。

## 跨系统一致性边界

Graph 数据库、状态库、消息队列和对象存储不是一个统一事务。需要更强的业务一致性时，可以组合：

- 持久化操作状态机；
- Outbox 事件；
- 幂等消费者；
- Saga 补偿；
- 周期对账；
- 人工修复入口。

不要因为方法在同一个 Java 调用栈中，就认为所有系统可以共同回滚。

## 常见问题

### 有分布式锁还需要 operationId 吗？

需要。锁只降低同时执行，无法处理写图后崩溃、锁租约到期或重复消息。

### operationId 相同会让 GraphWriter 自动跳过吗？

不会。当前通用 Writer 不保存 operationId。OperationStore 可以在已确认 GRAPH_APPLIED 后跳过 Writer，但最窄的崩溃窗口仍依赖 Mutation 可重放和对账。

### 为什么恢复不重新调用模型？

模型输出可能非确定。恢复必须执行首次审核和持久化的计划，否则同一个操作号会对应不同数据变化。

### InMemoryOperationStore 可以用于单实例生产吗？

不建议。进程退出后计划和阶段都会丢失，恰好无法处理恢复最需要覆盖的故障。

## 生产检查清单

- operationId 是否全局唯一且绑定单一计划；
- 操作和计划是否在同一存储事务中创建；
- 阶段推进是否原子 CAS；
- 文档状态是否有 revision CAS；
- Mutation 是否使用稳定节点与边身份；
- 是否识别写图成功但阶段未推进的未知窗口；
- 恢复是否读取原计划而非重新调用模型；
- 多实例是否具有租约、防重和持有者校验；
- Entity Registry 是否能处理并发创建冲突；
- 重试是否有分类、退避、上限和告警；
- 是否有图、文档状态、注册表之间的周期对账；
- 是否提供人工恢复和补偿流程。

模型自身的协议、隐私和真实测试要求见[模型接入](/zh/graph/knowledge-extraction-model-security)。

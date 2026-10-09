# 事务

## 概述

事务用于把一组相关的图查询和图写入放在同一个数据库执行边界中：所有操作共同提交，或者在失败时共同回滚。

例如，业务要求“撤销旧任职关系、建立新任职关系，并确认员工节点仍然有效”。如果三个步骤分别提交，中途失败就可能留下新旧关系并存或关系全部消失的状态；支持事务的后端可以让这些变化作为一个整体生效。

它与 MySQL 事务的核心目标相同，但 Graph SDK 只统一事务的生命周期和事务内 Writer/Query 入口，不统一不同图数据库的隔离级别、锁实现、死锁检测和分布式事务能力。后端不支持时，SDK 会明确报告能力缺失，不会静默模拟一个看似成功的事务。

## 什么时候需要事务

适合使用显式事务的场景包括：

- 先查询当前图状态，再根据结果执行条件变更；
- 同时替换多条关系，不能暴露中间状态；
- 多个节点和边必须共同创建或共同撤销；
- 写入后需要在同一事务中读取并校验结果；
- 小规模业务操作要求失败时完整回滚。

并非所有写入都需要显式事务。单个 Neo4j `mutate` 已经使用独立事务；普通 Upsert 又可以依靠稳定身份重复执行。只有当多个调用之间存在共同提交要求时，显式事务才提供额外价值。

以下场景通常不适合一个长事务：

- 数十万节点和边的全量导入；
- 需要等待人工审批、外部 API 或消息响应；
- 跨 HTTP 请求保存事务对象；
- 跨线程或跨异步任务共享数据库事务；
- 同时修改 MySQL、消息队列和图数据库并期待 SDK 自动提供分布式原子性。

这些场景更适合小批次、幂等操作、状态机、Outbox 或 Saga 补偿。

## 与 Mutation、导入的区别

三个概念解决不同层次的问题：

| 能力 | 主要用途 | 默认边界 |
| --- | --- | --- |
| `GraphMutation` | 表达一组有序节点/边变化 | 是否原子取决于后端和 Writer |
| `importData` | 将大量节点和边分批写入 | 每批独立，整体通常允许部分成功 |
| `GraphTransaction` | 让多个查询和写入共同提交 | 仅支持显式事务的后端可用 |

`GraphMutation` 是变更数据模型，不等于数据库事务。一个 Mutation 在 Neo4j 普通 Writer 中原子执行，但在当前 Nebula Writer 中可能由多条独立语句组成。

批量导入强调可恢复的小批次，不应为了“看起来更一致”就把整个导入包进一个事务。大型事务会放大锁、内存、超时和回滚成本。

## 使用前检查后端能力

显式事务是可选能力。面向多后端的应用应先检查：

~~~java
GraphCapabilities capabilities = graph.capabilities();
if (!capabilities.supports(GraphFeature.TRANSACTIONS)) {
    // 切换到幂等、补偿或其他明确设计的业务流程
}
~~~

也可以在应用启动或连接注册时读取完整能力矩阵，将限制记录到连接配置。不要等到一次关键业务操作中途才第一次发现后端不支持事务。

当前适配器中：

- Neo4j 支持 `GraphFeature.TRANSACTIONS`；
- Nebula SessionPool 适配器不暴露统一显式事务，调用 `transactions()` 会抛出 `UnsupportedGraphFeatureException`。

## 事务生命周期

一个事务经历以下阶段：

~~~text
begin
  -> 使用 transaction.writer() / transaction.query()
  -> commit   -> 关闭
  -> rollback -> 关闭
  -> close    -> 未提交时释放并回滚
~~~

推荐使用 try-with-resources，保证异常路径也能释放会话和连接：

~~~java
GraphOptions options = GraphOptions.builder()
    .space("company_knowledge")
    .timeoutMillis(10_000)
    .build();

try (GraphTransaction tx = graph.transactions().begin(options)) {
    GraphWriteResult write = tx.writer().mutate(mutation, options);
    if (!write.isSuccess()) {
        throw new IllegalStateException(
            write.getErrorCode() + ": " + write.getMessage(),
            write.getError());
    }

    GraphResult verification = tx.query().execute(query, options);
    if (verification.getRecords().isEmpty()) {
        throw new IllegalStateException("transaction verification failed");
    }

    tx.commit();
}
~~~

如果写入或验证抛出异常，try-with-resources 会调用 `close()`。Neo4j 实现关闭尚未提交的事务时，由驱动回滚事务。需要在业务代码中显式表达放弃也可以调用 `rollback()`；提交、回滚或关闭后，事务不能继续使用。

### 必须检查 GraphWriteResult

`GraphWriter.mutate(...)` 使用 `GraphWriteResult` 表示写入成功或失败，不能只依赖异常控制流。在事务中如果忽略 `isSuccess()==false` 并继续调用 `commit()`，就失去了业务层的失败判断。

因此，每次事务内写入都应检查结果，在失败时抛出异常或显式回滚。

## Space 和资源边界

事务在 `begin(options)` 时绑定目标 Space、数据库会话和超时。事务内的 Writer 和 Query 都使用这个已经绑定的数据库连接。

即使事务内方法仍然接收 `GraphOptions`，也不能借此把同一个事务切换到另一个 Space。建议从业务操作开始就创建一个不可变 options，并在 begin、query 和 mutate 中一致使用，便于日志、审计和代码检查。

事务对象及其 Writer/Query 具有严格生命周期：

- 不要缓存到单例字段；
- 不要放入 HTTP Session；
- 不要从控制器线程传递到后台线程；
- 不要在一个异步任务中创建、在另一个任务中提交；
- 不要在 commit、rollback 或 close 后继续调用；
- 不要让返回的惰性结果在事务关闭后才开始消费。

GraphStore 可以作为长期复用的连接门面；GraphTransaction 则是一次短业务操作的临时资源，两者生命周期完全不同。

## 事务内可见性

同一 Neo4j 事务中的 Query 和 Writer 绑定到同一个驱动事务。因此，事务内写入可以被之后的事务内查询看到，适合执行“写入后校验”：

~~~text
事务 A：写入节点 -> 查询节点 -> 可以看到本事务写入
事务 B：事务 A 提交前 -> 通常看不到未提交变化
事务 A：commit
其他事务：提交后才能观察到变化
~~~

这通常称为 read-your-writes。具体隔离行为、并发可见性、锁粒度和冲突处理由 Neo4j 版本与数据库配置决定，Graph SDK 不在公共 API 中承诺更强的统一隔离级别。

查询结果应在事务仍然打开时完整消费或物化。不要返回一个依赖底层事务游标的对象，让上层在事务关闭后才遍历。

## 提交、回滚和关闭

### commit

`commit()` 把当前事务中的变化提交到数据库，随后关闭事务与会话。提交后再次使用 `writer()`、`query()` 或重复提交会失败。

提交阶段也可能因连接中断、超时或集群故障失败。此时客户端观察到失败，并不总能仅凭异常确定服务端最终是否提交。业务应使用稳定实体身份、操作记录和提交后核验来处理未知结果，不要盲目执行非幂等重试。

### rollback

`rollback()` 放弃当前事务中的未提交变化并关闭资源。Neo4j 实现对已经关闭的事务重复 rollback 不再执行操作，因此可以在异常清理路径中安全调用。

回滚只影响当前图数据库事务，不会撤销事务期间已经调用的外部 HTTP API、发送的消息、写入的文件或其他数据库变化。

### close

`close()` 是资源安全网，不是业务成功信号。未调用 `commit()` 就关闭 Neo4j 事务时，不会提交当前变化。推荐始终使用 try-with-resources，避免连接长时间占用。

## 保持短事务

事务从 begin 到 commit/rollback 之间应只包含必要的数据库计算。开始事务前完成：

- 请求参数和权限校验；
- 文档解析和大模型调用；
- 远程服务查询；
- 人工确认；
- 大批量数据构造；
- 与事务无关的复杂业务计算。

事务打开后只执行少量、确定、可快速完成的读写。这样可以减少：

- 锁持有时间；
- 与其他写入的冲突；
- 驱动连接池占用；
- 超时和死锁概率；
- 失败后的重做成本。

`GraphOptions.timeoutMillis` 会传递给 Neo4j 事务配置。超时是保护措施，不是正常流程控制；应根据真实查询和写入延迟设置，并对超时进行监控和诊断。

## 并发、锁与冲突

两个事务同时更新相同节点或关系时，数据库可能串行化更新、等待锁、报告死锁或使后提交结果覆盖同名属性。Graph SDK 不会自动解决业务冲突。

常见策略包括：

- 使用稳定节点 ID 和唯一约束，防止并发创建重复实体；
- 为业务对象维护 revision，在事务中验证期望版本；
- 按稳定顺序访问多个实体，降低死锁概率；
- 对热点实体使用上层串行队列或有租约的分布式锁；
- 对可重试冲突采用有上限的指数退避；
- 对未知提交结果先查询操作状态，再决定是否重试。

自动重试必须重新创建完整事务，不能在已经失败或关闭的事务对象上继续。重试代码还必须确保事务中的业务逻辑可重复执行，尤其不能重复调用非幂等外部服务。

## 事务与批量导入

Neo4j 事务绑定的 Writer 也能调用同步 `importData`，此时各批次实际加入同一个显式事务，直到外层 commit 才提交。技术上可行并不代表适合全量导入：

- 所有批次共同占用一个长事务；
- 数据越多，内存、锁和超时风险越高；
- 最后一批失败会让全部工作回滚；
- `stopOnError=false` 仍要求调用方检查报告，否则可能提交带失败记录的流程；
- 异步导入任务不应跨线程复用这个事务对象。

只有数据规模很小、原子性价值明确且经过真实环境压测时，才考虑事务内导入。常规大规模导入应使用独立小批次、checkpoint、幂等重试和完成后对账。

## Neo4j 事务实现

Neo4j 适配器在 begin 时：

1. 根据 `GraphOptions` 打开目标 database 的 Session；
2. 使用 `timeoutMillis` 创建驱动 Transaction；
3. 创建绑定该 Transaction 的 Writer 和 Query；
4. 在 commit、rollback 或 close 时关闭事务与 Session。

普通 `graph.writer().mutate(...)` 也会为单次调用创建事务，但它无法与另一次 Query 或 Mutation 共同提交。需要跨调用原子性时，必须使用 `transaction.writer()` 和 `transaction.query()`，不要误用 Store 上的普通入口。

## Nebula 没有统一显式事务时怎么办

当前 Nebula SessionPool 适配器会明确拒绝 `transactions()`，调用方应选择符合业务风险的替代方案，而不是捕获异常后假装已经获得事务保证。

### 缩小为单条原生操作

如果业务变化可以由一条经过验证的 nGQL 完成，可以利用该语句自身的后端语义。但这属于 Nebula 专属实现，必须验证版本、错误行为和并发效果。

### 幂等 Upsert 与操作日志

为每个业务操作分配稳定 operationId，在应用数据库中保存：

~~~text
PENDING -> APPLYING -> APPLIED
                    -> FAILED
                    -> COMPENSATING -> COMPENSATED
~~~

节点和边使用稳定身份 Upsert。进程恢复时读取未完成操作，核验图状态后继续或补偿。operationId 需要由上层持久化；仅设置在 `GraphMutation` 上不会自动形成去重记录。

### Saga 与补偿

为每个步骤设计可重复的正向操作和补偿操作。例如先记录旧关系，再删除旧关系、写入新关系；失败时根据操作日志恢复旧关系。补偿不是数据库回滚，执行期间可能被其他查询观察到，因此需要明确业务状态和可见性设计。

### Outbox 与最终一致性

当关系数据源于 MySQL 业务事务时，可以在 MySQL 中把业务变化和 Outbox 事件共同提交，再由消费者幂等同步到图数据库。图是查询投影而不是唯一事实源时，这通常比尝试跨数据库 XA 更可靠。

无论采用哪种方案，都应提供对账任务、失败告警和人工修复入口。

## 事务之外的副作用

GraphTransaction 只控制图数据库中的操作。下面这些行为不会随图事务自动回滚：

- 写入关系数据库或缓存；
- 发送消息和邮件；
- 调用第三方 API；
- 上传或删除对象存储文件；
- 修改应用自己的任务状态表。

需要跨系统一致性时，应明确事实源和提交顺序，使用 Outbox、Saga、幂等消费者或对账修复。不要因为代码写在同一个 Java `try` 块中，就把多个系统误认为处于同一个事务。

## 常见问题

### 每次写入都要手动开启事务吗？

不需要。单次 Neo4j `mutate` 已经有自己的事务边界。只有多个读写需要共同提交、或需要事务内 read-your-writes 时，才使用显式事务。

### commit 后还需要 close 吗？

Neo4j 实现的 commit 会关闭事务资源，但仍推荐 try-with-resources。它能统一覆盖 begin 后的所有异常路径，重复 close 是幂等的。

### close 是否一定等价于 rollback？

公共接口约定实现通常会回滚未完成事务；当前 Neo4j 实现通过关闭未提交的驱动事务实现这一点。业务代码仍应把 commit 作为唯一成功路径，不依赖 close 表达业务意图。

### 能把事务保存起来，等用户下一次请求再提交吗？

不能。事务占用会话和连接，也不适合跨线程、跨请求存活。需要人工确认时，先把“待执行计划”保存到应用数据库，确认后再开启一个新的短事务执行。

### 能在一个事务中切换 Space 吗？

不能。Space 在 begin 时绑定。需要修改多个 Space 时，应分别执行，并通过上层状态机和补偿协调。

### Nebula 会自动降级为非事务执行吗？

不会。SDK 会抛出 `UnsupportedGraphFeatureException`，避免调用方误以为操作具有原子性。

## 生产检查清单

- 启动或连接注册时是否检查 `GraphFeature.TRANSACTIONS`；
- 是否只为确实需要共同提交的小规模操作使用显式事务；
- begin 时是否绑定可信的 Space 和合理超时；
- 事务内是否始终使用 transaction.writer/query，而非 Store 普通入口；
- 每个 `GraphWriteResult` 是否检查 success 和错误码；
- 所有成功路径是否明确 commit，所有异常路径是否关闭或回滚；
- 是否使用 try-with-resources 防止连接泄漏；
- 是否避免跨线程、跨请求和跨异步任务传递事务；
- 大模型、远程 API 和耗时计算是否放在 begin 之前；
- 是否评估热点实体的锁竞争、死锁和最后写入覆盖；
- 重试是否重新创建事务，并限制次数、退避和幂等范围；
- 是否处理提交结果未知，而不是直接重复非幂等操作；
- 是否避免用一个长事务承载全量导入；
- Nebula 路径是否有操作日志、补偿、对账和失败告警；
- 跨数据库或消息系统的一致性是否使用明确的 Outbox/Saga 设计。

事务解决的是支持后端中的短期原子读写，不替代批量导入恢复、长期任务状态或跨系统一致性。大规模数据建设应继续采用[批量导入](/zh/graph/batch-import)的分批和 checkpoint 机制。

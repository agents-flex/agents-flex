# 异步导入任务

## 概述

同步导入会一直占用当前线程，直到所有批次完成或发生错误。对于需要在业务后台提交任务、持续查看进度、请求取消和从确认位置恢复的场景，Graph SDK 提供 `GraphImportService`。

`graph.imports()` 返回当前 GraphStore 关联的异步导入服务。它复用统一的节点优先、边随后和分批写入语义，同时在进程内维护任务状态、累计报告、确认偏移和资源生命周期。

默认实现适合单实例 SDK 或应用内作业执行，不等价于分布式任务平台。文件解析、持久化调度、跨实例抢占、失败队列和自动重启恢复仍由开发者自己的系统负责。导入模型、批次和数据质量要求见[批量导入](/zh/graph/batch-import)。

## 适用场景

异步导入适合：

- HTTP 请求提交任务后立即返回任务 ID；
- 后台管理系统轮询或展示导入进度；
- 导入过程中允许用户请求取消；
- 每个成功批次后持久化确认偏移；
- 使用文件、数据库游标等需要统一释放的外部数据源；
- 通过监听器接入日志、指标和应用事件。

如果应用已经使用成熟的调度平台，也可以由调度平台直接调用同步 `writer().importData(...)`，并自行管理任务状态。异步服务不是必须经过的封装。

## 提交和读取任务

~~~java
GraphImportRequest request = GraphImportRequest.builder()
    .source(source)
    .batchSize(500)
    .stopOnError(true)
    .build();

GraphImportTask submitted = graph.imports().submit(
    request,
    GraphOptions.ofSpace("company_knowledge"));

GraphImportTask latest = graph.imports().get(submitted.getId());
while (latest != null && !latest.isTerminal()) {
    Thread.sleep(200L);
    latest = graph.imports().get(submitted.getId());
}
~~~

`submit` 返回的是提交时刻的只读快照。任务在后台继续变化时，旧对象不会自动更新；调用方需要通过任务 ID 再次调用 `get`。

目标 Space 和数据源版本应在提交后保持不变。不要把一个任务 ID 改绑到另一 Space，也不要在后台执行期间修改其输入文件。

## 状态生命周期

任务状态按以下路径变化：

~~~text
QUEUED -> RUNNING -> SUCCEEDED
                  -> FAILED
                  -> CANCELLED
~~~

| 状态 | 含义 |
| --- | --- |
| `QUEUED` | 已接受任务，等待执行器调度 |
| `RUNNING` | 正在消费节点或边批次 |
| `SUCCEEDED` | 所有批次成功完成 |
| `FAILED` | 出现未恢复错误，或最终报告包含失败批次 |
| `CANCELLED` | 服务接受了取消请求或关闭时终止任务 |

任务进入终态后不会自动从存储中删除。应用可以根据审计和保留策略调用 `remove` 或 `purgeCompletedBefore`。

## 任务快照

`GraphImportTask` 包含：

- 任务 ID 和当前状态；
- 提交、开始和完成时间；
- 状态消息；
- 累计 `GraphImportReport`；
- 已成功确认的节点数量；
- 已成功确认的边数量；
- 已处理批次序号；
- 可用于重新提交的 `GraphImportResumePoint`。

报告和偏移是运行时观察结果，不是数据库中真实数据量的证明。重复 Upsert、后端内部部分成功和 checkpoint 持久化失败都可能造成差异，任务结束后仍需执行数据质量验证。

## 任务操作

| 方法 | 说明 |
| --- | --- |
| `submit` | 提交任务并立即返回快照 |
| `get` | 根据 ID 读取最新任务快照 |
| `list` | 按提交时间倒序列出任务 |
| `cancel` | 请求取消排队或运行中的任务 |
| `remove` | 删除一个已结束任务 |
| `purgeCompletedBefore` | 清理完成时间早于指定时刻的终态任务 |
| `close` | 停止服务、取消未结束任务并释放执行器 |

`remove` 只删除任务记录，不会删除已经写入图数据库的节点和边。运行中任务不能通过 remove 强制消失，应先使用 cancel，并等待任务进入终态。

## 进度监听

`GraphImportListener` 可以接收状态进度、批次完成和未恢复错误：

~~~java
GraphImportListener listener = new GraphImportListener() {
    @Override
    public void onProgress(GraphImportTask task) {
        metrics.record(task.getId(), task.getStatus());
    }

    @Override
    public void onBatchCompleted(
            GraphImportTask task,
            GraphWriteResult result) {
        audit.recordBatch(task.getId(), result.isSuccess());
    }

    @Override
    public void onError(GraphImportTask task, Throwable error) {
        alerts.report(task.getId(), error);
    }
};
~~~

监听器在导入执行路径中回调，应快速返回，避免执行耗时 I/O 或等待其他任务。监听器抛出的运行时异常会被 SDK 隔离，不改变导入任务状态；因此监听器自身的失败需要独立监控。

## Checkpoint 和恢复

异步服务只在整个批次成功后增加确认偏移，并调用 checkpoint：

~~~java
GraphImportRequest request = GraphImportRequest.builder()
    .source(source)
    .checkpoint((taskId, nodesProcessed, edgesProcessed) ->
        checkpointStore.save(
            taskId, nodesProcessed, edgesProcessed))
    .build();
~~~

恢复时应重新打开同一版本、同一顺序的数据源，再把已保存偏移传给 `resumeFrom`。SDK 从数据流开头跳过已确认记录，不会保存文件句柄，也不会自动定位外部数据库游标。

这种 offset 恢复只适合表示连续成功的数据前缀，推荐与 `stopOnError=true` 配合。允许失败后继续时，成功记录之间可能出现空洞，单一节点/边数量无法描述具体失败批次；上层必须另存失败清单并按清单重放。

checkpoint 回调失败不会让导入任务失败。生产实现必须监控保存结果，并接受“数据库批次已成功，但 checkpoint 尚未持久化”这一窗口。恢复时重复处理该批次应依靠稳定节点 ID 和边身份收敛。

更完整的恢复前提和部分成功语义见[批量导入](/zh/graph/batch-import#checkpoint-与恢复)。

## 取消的真实含义

`cancel(id)` 会标记取消状态、中断执行线程并关闭数据源，但不能撤销已经提交的数据库批次。数据库正在执行的语句也可能在取消信号到达后才完成。

因此取消后的图可能包含：

- 已完整提交的节点批次；
- 已完整提交的边批次；
- Nebula 失败或取消窗口中已经生效的部分语句；
- 尚未处理的数据。

需要撤销整个导入时，必须基于导入批次、来源事实或独立 Space 设计清理流程，不能把 cancel 当作 rollback。

## 数据源资源

`GraphImportSource` 可以持有文件、网络流或数据库游标。异步任务成功、失败、取消或服务 close 时，SDK 会尝试关闭 source，并通过内部保护避免重复关闭。

自定义 source 仍应满足：

- `close()` 幂等；
- 节点和边 Iterable 的消费顺序确定；
- 恢复时能够重新创建，而不是复用已经关闭的实例；
- 关闭时能安全处理正在阻塞的读取；
- 不由提交请求的局部清理逻辑提前关闭。

一个 source 实例不应同时提交给多个任务。需要并行导入时，应为每个任务创建独立数据源。

## 默认执行器和并发

默认 `AsyncGraphImportService` 使用单线程执行器，同一个 Store 的任务按执行器调度顺序运行。这有助于减少默认配置下的资源争抢，但不构成全局串行保证：不同 Store、不同进程或自定义线程池仍可并发写入同一 Space。

可以注入自定义 `ExecutorService`，但增加并发前应评估：

- 数据库连接池和写入吞吐；
- 同一实体的并发属性覆盖；
- Schema 或索引传播期间的失败；
- 租户级限流和公平性；
- cancel、close 与执行器生命周期；
- JVM 停止时的优雅关闭时间。

并发任务更新同一节点或边时，公共导入器不提供 revision 冲突检测。需要由上层分区、加锁或采用文档级状态机。

## 任务存储与重启边界

默认 `InMemoryGraphImportTaskStore` 只在当前进程内保存快照，进程退出后任务记录丢失。

可以实现自己的 `GraphImportTaskStore` 持久化任务快照，但仅持久化快照仍不等于自动恢复执行。当前默认服务启动后不会扫描旧的 RUNNING 任务、重新打开数据源、获取分布式租约并继续处理。

需要生产级跨实例作业时，上层执行系统还应负责：

- 持久化任务请求和不可变数据源版本；
- 任务认领、租约、续约和超时回收；
- 进程崩溃后的状态判定；
- 根据 checkpoint 重新创建数据源并提交；
- 防止同一任务被多个实例同时执行；
- 重试预算、死信、告警和人工恢复；
- 任务记录的保留、审计和权限控制。

Graph SDK 可以继续作为每个批次的统一写入层，但不会替代这些分布式调度职责。

## 与 GraphStore 生命周期的关系

异步服务由 GraphStore 持有。关闭 Store 时会关闭异步服务、取消未结束任务并释放执行线程。

因此：

- GraphStore 应作为应用级长期资源复用；
- 不要在提交任务后立即关闭 Store；
- Spring 中使用 `@Bean(destroyMethod = "close")` 时，容器关闭或 Bean 被销毁会触发任务服务关闭；
- 动态切换连接配置时，应先停止向旧 Store 提交任务，等待或迁移任务，再关闭旧实例；
- 应用优雅停机时间应覆盖 source 关闭和正在执行批次的退出。

## 常见问题

### 默认异步服务支持应用重启后续跑吗？

不支持。默认任务和执行器都在进程内。自定义持久化 TaskStore 只能保存快照；自动续跑还需要上层调度、数据源重建、租约和幂等控制。

### cancel 后为什么数据库里还有数据？

取消不是事务回滚。取消前成功的批次会保留，正在执行的语句也可能完成。需要按来源或批次撤销时，应单独设计清理计划。

### 可以从 listener 中修改任务状态吗？

不可以。listener 用于观察和集成监控，不是任务控制接口。状态由异步服务维护；取消使用 `cancel`。

### 为什么任务状态是 SUCCEEDED，数量却与源文件不同？

报告统计的是成功 Writer 批次的逻辑处理数，不是物理新增数。重复实体会 Upsert，源文件也可能在转换阶段过滤、合并或拆分。应按映射规则和数据库查询对账。

### 可以提交后立即关闭 GraphImportSource 吗？

不可以。后台线程还需要消费它。异步服务会在任务终态、取消或服务关闭时负责关闭；调用方只需保证 source 自身支持幂等 close。

## 生产检查清单

- 是否确实需要 SDK 内异步服务，还是已有作业平台更合适；
- 提交时是否固定 Space、Schema 版本、数据源版本和任务业务 ID；
- 是否保存返回的任务 ID，并通过 get 获取新快照；
- listener 和 checkpoint 是否快速、可监控且不会阻塞导入线程；
- checkpoint 是否持久化，恢复数据源是否可按同一顺序重建；
- 是否明确 cancel 不回滚，并具备撤回或对账方案；
- GraphImportSource 是否独占、可关闭、可重新创建；
- 自定义执行器的并发度是否经过真实数据库压测；
- 是否避免多个任务无保护地更新同一实体；
- 任务存储、调度执行和自动恢复的职责是否明确区分；
- 多实例部署是否具备租约、防重和崩溃恢复；
- Store 动态替换或应用停机时是否妥善处理运行任务；
- 终态任务是否有保留、清理、审计和权限策略；
- 导入完成后是否执行独立的数据质量验证。

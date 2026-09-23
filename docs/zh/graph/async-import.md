# 异步导入任务

GraphStore.imports 返回 GraphImportService。默认实现使用进程内执行器，适合单实例 SDK 场景，不等价于分布式
任务系统。

## 提交和轮询

~~~java
GraphImportTask submitted =
    graph.imports().submit(request, GraphOptions.ofSpace("neo4j"));

GraphImportTask latest = graph.imports().get(submitted.getId());
while (latest != null && !latest.isTerminal()) {
    Thread.sleep(200L);
    latest = graph.imports().get(submitted.getId());
}
~~~

状态流转：

~~~text
QUEUED -> RUNNING -> SUCCEEDED
                  -> FAILED
                  -> CANCELLED
~~~

## 任务操作

| 方法 | 说明 |
| --- | --- |
| submit | 提交任务并立即返回快照 |
| get | 读取最新任务快照 |
| list | 列出任务 |
| cancel | 请求取消运行中的任务 |
| remove | 删除已结束任务 |
| purgeCompletedBefore | 清理过期终态任务 |

任务快照包含累计报告、已确认节点/边偏移、批次数和恢复点。取消不是数据库回滚；已经成功写入的批次仍然存在。

## 持久化边界

默认 InMemoryGraphImportTaskStore 在进程退出后丢失。需要跨实例查询、重启恢复、租约和分布式调度时，应实现
自己的 GraphImportTaskStore 和执行层，并将 SDK 作为批次写入器使用。

## 资源释放

GraphImportSource 可以持有文件、网络流或其他资源。任务成功、失败、取消或服务 close 时，SDK 会尝试关闭数据源；
自定义资源仍应实现幂等 close。


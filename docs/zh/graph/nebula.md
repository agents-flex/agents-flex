# Nebula Graph 适配器

Nebula 适配器位于 agents-flex-graph-nebula，基于官方 SessionPool 客户端实现 GraphStore。它把 GraphOptions.space 映射为 Nebula space。

## 连接配置

~~~java
NebulaGraphStoreConfig config = new NebulaGraphStoreConfig()
    .setHost("127.0.0.1")
    .setPort(9669)
    .setUsername("root")
    .setPassword("nebula")
    .setDefaultSpace("agents_flex")
    .setMinSessions(1)
    .setMaxSessions(10);

try (GraphStore store = new NebulaGraphStore(config)) {
    GraphHealth health = store.health();
}
~~~

NebulaGraphStore 按 space 缓存 SessionPool。关闭 Store 后，所有池和异步导入资源都会被释放，不能再次隐式重建。

## space、TAG 和 EDGE

GraphManager.createSpace 会创建 Nebula space；Schema 中的节点类型编译为 TAG，边类型编译为 EDGE。Nebula 的属性定义支持增量补充，索引创建后适配器会尝试重建索引并等待可用。

Nebula 的 Portable Writer 当前只支持单 TAG 节点。GraphFeature.MULTI_LABEL 不代表写入器可以像 Neo4j 一样在同一节点上写入多个标签；需要多 TAG、特殊 VID 或 Nebula 专有语法时，应使用 NativeGraphQuery，并在目标版本上编写测试。

## 索引与过滤

Nebula 的属性过滤依赖有效的 TAG/EDGE 索引。创建索引后必须等待重建完成，否则 MATCH 或过滤查询可能失败或看不到预期结果。GraphSchemaInspection 不返回可移植的索引元数据，因此反查结果可能不完整。

不要把没有索引的属性过滤当作一定会自动全图扫描。产品服务应在查询提交前根据 GraphCapabilities、Schema 和业务规则提示用户创建索引或调整查询。

## 查询、事务与游标

Nebula 支持 nGQL 原生查询、变长路径和 Explain。SessionPool 不提供统一的显式事务边界，因此 store.transactions() 会抛出 UnsupportedGraphFeatureException。需要原子写入时，应使用单条 nGQL 语句的后端语义，或由上层设计幂等补偿流程。

SessionPool 返回物化 ResultSet。executeCursor 仍满足统一接口，但当前是内存物化结果包装，不是数据库级流式游标。大量查询必须限制 maxRecords、分页或拆分批次。

timeoutMillis 在 Nebula 上没有与 Neo4j 事务超时等价的单查询强制参数。它不能作为“服务端一定在指定时间终止”的承诺。

## 批量导入和运维

当前批量导入模式为 ONLINE_BATCH，使用在线 UPSERT 批次，不封装 Nebula 离线导入工具。导入前应先创建 space、TAG、EDGE 和索引，导入后执行 count、抽样遍历和完整性检查。

生产环境至少应：

- 使用专用账号和密钥管理；
- 检查 Graph service、Meta service、Storage service 和端口连通性；
- 监控 SessionPool 大小、失败重试和请求耗时；
- 在删除 space 前执行备份或人工审批；
- 关闭 GraphStore，避免进程退出时遗留会话。

相关章节：[Schema 定义](/zh/graph/schema)、[后端能力对比](/zh/graph/backend-comparison)、[Docker 真实环境测试](/zh/graph/docker-integration-testing)。

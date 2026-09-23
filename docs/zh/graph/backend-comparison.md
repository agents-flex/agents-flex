# 后端能力对比

Graph API 统一的是调用方式和结果契约，不是把两个数据库伪装成完全相同的产品。上线前应读取 store.capabilities().matrix()，根据实际能力决定是否展示或启用功能。

## 能力矩阵

| 能力 | Neo4j | Nebula | 调用方注意 |
| --- | --- | --- | --- |
| 创建/删除空间 | Enterprise + 管理权限 | 支持 space 管理 | Community Neo4j 可能失败 |
| Schema | 支持 | 支持 TAG/EDGE | DDL 语义不同 |
| Schema 反查 | 支持，端点标签可能不完整 | TAG/EDGE 可反查，索引元数据不完整 | 检查 GraphSchemaInspection.isComplete |
| 普通索引 | 支持 | 支持 | Nebula 创建后需重建并等待 |
| 唯一约束 | 支持 | 不提供可移植唯一约束 | 业务 ID 唯一性需由写入策略保证 |
| 多标签 | 支持 | Portable Writer 单 TAG | 多 TAG 使用原生 nGQL |
| 变长路径 | 支持，统一上限 16 跳 | 支持，最大跳数由后端定义 | 控制查询成本 |
| 最短路径 | 原生查询 | 原生查询 | 没有统一 DSL |
| 显式事务 | 支持 | SessionPool 不支持统一事务 | 不要在 Nebula 假设跨语句原子性 |
| 在线批量导入 | 支持 | 支持 | 都不是离线导入工具 |
| Explain | 支持 | 支持 | 计划结构不兼容 |
| 流式游标 | 驱动流式 | 当前为物化结果包装 | Nebula 查询必须限制结果规模 |
| 原生查询 | Cypher | nGQL | 语句不可移植 |

## 能力检查

~~~java
GraphCapabilityDetail detail =
    graph.capabilities().describe(GraphFeature.TRANSACTIONS);

if (!detail.isSupported()) {
    // 禁用事务按钮，并展示 detail.getNote()。
}
~~~

不支持能力应由 SDK 抛出 UnsupportedGraphFeatureException，而不是静默退化。上层可以在产品配置阶段把 capability matrix 缓存为功能开关，但每次真正执行仍应保留异常处理。

## 统一代码的边界

以下能力适合使用公共 API：节点和边 upsert、稳定 ID 删除、有限跳遍历、属性过滤、常用聚合、offset/opaque 分页、健康检查和在线批量导入。

以下能力应按后端分支：Cypher/nGQL、最短路径、数据库管理语句、多标签写入、唯一性约束的强保证、复杂执行计划解析和大结果集流式处理。

不要只以类名判断后端能力。例如 NebulaGraphStore 也实现了 GraphFeature.CREATE_SPACE，但这不等于当前部署账号一定能创建 space；能力表示协议支持，权限和版本仍需通过真实测试验证。

## 数据迁移策略

从 Neo4j 迁移到 Nebula 时，应重新设计标签、VID、边 rank、索引和事务边界。不要直接把 Cypher 文本替换为 nGQL 文本。推荐流程是：

1. 以 GraphSchema 作为逻辑模型；
2. 用各适配器分别 applySchema；
3. 用 GraphImportRequest 分批导入；
4. 对同一组业务断言执行统一查询；
5. 对原生查询和后端专有能力单独验收。

相关章节：[能力声明](/zh/graph/capabilities)、[Neo4j](/zh/graph/neo4j)、[Nebula Graph](/zh/graph/nebula)。

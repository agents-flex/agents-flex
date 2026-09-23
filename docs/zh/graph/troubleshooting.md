# 故障排查

排查 Graph 问题时，先区分连接、空间、Schema、查询、资源和业务数据六类问题。不要只根据数据库异常文本判断错误类型，优先查看 GraphErrorCode、GraphHealth、GraphCapabilities 和 GraphResultMetadata。

## 连接失败

现象：health 返回 DOWN，或首次执行时无法创建驱动连接。

检查顺序：

1. 确认主机、端口、URI、用户名和密码来自当前环境，而不是示例默认值。
2. 确认容器健康状态和服务端口已就绪。
3. Neo4j 检查 Bolt URI、database 权限和 TLS；Nebula 检查 Graph service、Meta service、Storage service 和 SessionPool 配置。
4. 记录后端版本、连接名称和脱敏后的配置摘要。
5. 关闭并重新创建测试 Store，避免把已 close 的实例继续复用。

## 空间或数据库不存在

Neo4j 的空间是 database，Nebula 的空间是 space。GraphOptions.space 只负责路由，不会自动修复拼写错误。Neo4j Community 不能保证通过 SDK 创建或删除 database；Nebula space 创建也需要账号拥有管理权限。

使用 graph.manager().listSpaces() 和 spaceExists() 验证目标名称。若管理操作失败，区分“不存在”“没有权限”和“部署版本不支持”，不要直接重试破坏性操作。

## Schema 或索引失败

先调用 validateSchema，再检查 GraphSchemaApplyResult 的步骤、错误码和 warning。Nebula 属性过滤失败时，确认对应 TAG/EDGE 索引已经创建、重建并完成；Neo4j 则检查索引或约束状态和端点标签推断是否完整。

Schema 反查不完整不等于数据库为空。GraphSchemaInspection.isComplete 为 false 时，应把 unsupportedMetadata 和 warnings 传递给上层，避免自动删除未知定义。

## 查询返回空或结果不完整

- 检查 GraphOptions.space 是否指向正确空间；
- 检查标签、边类型、方向、rank 和属性名称的大小写；
- 检查过滤值的 Java 类型和空值语义；
- 检查 maxRecords、分页 offset 和 cursor 是否被复用到另一条查询；
- 检查 GraphResultMetadata.isTruncated；
- Nebula 检查索引是否可用以及服务端 schema 是否已传播。

结果为空时不要自动扩大查询范围或删除过滤条件；先用小范围 NativeGraphQuery 和 count 查询验证数据是否存在。

## 超时、重试与重复写入

GraphOptions.timeoutMillis 在 Neo4j 上会下推到事务，在 Nebula 上不提供等价的服务端硬超时。调用方不能因为客户端等待超时就断言后端没有执行成功。

写入应使用稳定业务 ID 和幂等 upsert。重试前记录请求 ID、批次号和 checkpoint；对不确定结果的写操作先用查询确认，再决定是否重试。

## 导入任务卡住

查看 GraphImportTask 的状态、累计报告、错误列表和 getResumePoint。失败批次不应推进 checkpoint。进程内默认任务服务不适合跨实例恢复；需要持久化和分布式调度时，提供自己的 GraphImportTaskStore 和任务执行器。

关闭服务时先停止接收新任务，再等待或取消运行任务，最后关闭 GraphStore。不要在任务线程中持有已关闭的 SessionPool 或 Driver。

## 游标和内存问题

Neo4j 流式游标必须关闭。Nebula 当前把 ResultSet 物化后包装为 GraphResultCursor，因此大结果仍会占用内存。降低 maxRecords、使用分页、增加过滤条件，或改用后端专有的分批方案。

## 诊断信息

建议每次失败记录：

- GraphErrorCode、backend、space 和 capability；
- 请求 ID、Schema 版本、GraphQueryKind；
- 耗时、maxRecords、是否截断；
- 脱敏后的 queryText 和执行计划摘要；
- 容器或数据库版本、连接池指标。

不要记录密码、访问令牌和未经脱敏的用户属性。

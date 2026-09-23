# 单元测试与契约测试

Graph SDK 的测试应分层：公共 API 的纯逻辑测试、适配器编译测试、跨后端契约测试和真实数据库集成测试。只测试“能返回一行结果”不足以覆盖 Schema、资源生命周期、分页和能力差异。

## 公共 API 单元测试

不启动数据库，验证以下不变量：

- GraphNode、GraphEdge、GraphEdgeKey 的 ID、rank、属性和相等性；
- 标识符校验拒绝空白、非法字符和注入片段；
- GraphOptions 的默认值、边界值、readOnly 和不可变副本；
- GraphSchema 拒绝重复标签、边类型、属性、索引和索引字段；
- GraphSchemaComparator 和 GraphSchemaMigrationPlanner 的新增、兼容和破坏性变更；
- GraphFilter 的 AND、OR、NOT、比较、IN、NULL 和参数绑定；
- GraphResult、GraphRecord、GraphSubgraphResult 的只读性、去重、截断和元数据；
- GraphPageRequest、GraphPageResult 和 opaque cursor 的格式校验；
- GraphCapabilities 的 supports、limits、modes、matrix 和 require；
- GraphImportReport、checkpoint、resume point 及失败批次不推进偏移。

## 编译器测试

Neo4jCypherCompiler 和 NebulaNqlCompiler 应使用参数化断言测试：

- 标签、边类型、方向、有限跳数和投影；
- 属性值只出现在参数映射，不直接拼接；
- 非法标识符被拒绝；
- 聚合、排序、分页和 Union；
- 单 TAG 写入、rank 和属性类型；
- Neo4j 与 Nebula 的语句差异及错误提示。

断言应同时检查语句结构和参数值，避免只比较一段脆弱的完整字符串。

## 适配器契约测试

为两个适配器共享同一组行为断言：

1. 健康检查成功和连接失败；
2. 创建、复用、列出和删除空间；
3. Schema ADDITIVE 幂等执行、反查和 warning；
4. 节点/边 upsert、更新、删除和 detach delete；
5. 过滤、遍历、聚合、Union、分页和空结果；
6. maxRecords 截断、queryText 和 metadata；
7. 原生 READ/WRITE 与 readOnly 保护；
8. Explain 和不支持能力异常；
9. 导入报告、失败记录、取消和恢复点；
10. close 后资源释放及重复 close 的幂等性。

对于不能统一的项目，要显式写成后端特定测试：Neo4j 事务提交/回滚和流式游标，Nebula 单 TAG、索引重建、无显式事务和物化游标。

## 属性与并发测试

使用随机节点、边、过滤条件和分页参数测试：

- 同一业务 ID 重复 upsert 的幂等性；
- 并发写入同一节点或同一边时的最终状态；
- 分页 token 不重复、不遗漏且不能跨查询复用；
- 异步导入关闭期间不泄漏线程；
- 超时、断连和后端重试不会产生无法解释的重复写入。

## 测试数据与清理

每个测试使用唯一前缀的空间或标签，避免并行测试互相污染。优先在 finally 中删除测试数据；无法删除时使用一次性 Docker volume。测试日志应脱敏密码、token 和完整原生查询参数。

## 验收标准

提交适配器改动时，至少通过公共模块单元测试、两个编译器测试、可用后端的契约测试和真实 Docker 集成测试。只有代码覆盖率达标但没有真实数据库断言，不能视为 Graph 功能验收完成。

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
10. close 后资源释放及重复 close 的幂等性；
11. 同一节点或同一边的并发 upsert 最终只保留一个业务键，不同业务键仍可并行写入。

对于不能统一的项目，要显式写成后端特定测试：Neo4j 事务提交/回滚和流式游标，Nebula 单 TAG、索引重建、无显式事务和物化游标。

## 属性与并发测试

使用随机节点、边、过滤条件和分页参数测试：

- 同一业务 ID 重复 upsert 的幂等性；
- 并发写入同一节点或同一边时的最终状态；
- 分页 token 不重复、不遗漏且不能跨查询复用；默认 token 绑定查询结构指纹，把某条查询的 token
  用到另一条过滤、排序或投影不同的查询时必须拒绝；
- 异步导入关闭期间不泄漏线程；
- 超时、断连和后端重试不会产生无法解释的重复写入。

当前 API 测试还使用固定种子生成节点、边、rank、过滤树和分页形状，验证实体键、查询校验和结果截断的不变量；
过滤条件会在构建时冻结集合、数组和 Map，防止调用方后续修改查询语义。

## 增量抽取恢复测试

`agents-flex-graph-extractor` 使用故障注入测试覆盖 PREPARED、GRAPH_APPLIED、STATE_COMMITTED 和
COMPLETED 的完整恢复路径。重点验证以下故障窗口：

- GraphWriter 抛异常或返回失败；
- 图已经写入，但 GRAPH_APPLIED 日志 CAS 失败；
- 实体注册、文档状态 CAS 或历史快照保存失败；
- 文档状态已提交，但操作日志尚未推进；
- 新服务实例从首次冻结的计划恢复，不重新调用模型；
- 同一文档并发导入只产生一次有效写入，不同文档仍可并行。

离线执行 Graph 全部单元和契约测试：

```bash
env -u DEEPSEEK_API_KEY mvn -f agents-flex-graph/pom.xml test
```

`agents-flex-graph-extractor` 在 Maven `verify` 阶段生成 JaCoCo 报告，并执行最低 85% 行覆盖率、60%
分支覆盖率门禁。门禁用于防止覆盖率回退，不能替代故障注入或真实数据库断言：

```bash
env -u DEEPSEEK_API_KEY mvn \
  -pl agents-flex-graph/agents-flex-graph-extractor -am verify
```

## 持久化扩展契约 Testkit

`agents-flex-graph-testkit` 提供三个可继承的 JUnit 4 契约基类：

- `AbstractGraphDocumentStateStoreContractTest`；
- `AbstractGraphIngestionOperationStoreContractTest`；
- `AbstractGraphIngestionLockProviderContractTest`。

持久化或分布式实现只需覆盖工厂方法并为每个测试返回空的隔离实例。例如：

```java
public class JdbcOperationStoreContractTest
    extends AbstractGraphIngestionOperationStoreContractTest {
    @Override
    protected GraphIngestionOperationStore createStore() {
        return new JdbcGraphIngestionOperationStore(testDataSource);
    }
}
```

操作日志契约面向支持完整恢复的实现，要求 operation 与原始 plan 原子保存；只实现兼容层基础方法、
无法保存 plan 的旧实现不能通过该契约。

## 测试数据与清理

每个测试使用唯一前缀的空间或标签，避免并行测试互相污染。优先在 finally 中删除测试数据；无法删除时使用一次性 Docker volume。测试日志应脱敏密码、token 和完整原生查询参数。

## 验收标准

提交适配器改动时，至少通过公共模块单元测试、两个编译器测试、可用后端的契约测试和真实 Docker 集成测试。只有代码覆盖率达标但没有真实数据库断言，不能视为 Graph 功能验收完成。

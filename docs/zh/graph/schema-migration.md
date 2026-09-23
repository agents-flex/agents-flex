# Schema 增量迁移

Graph SDK 提供 Schema 比较和迁移计划模型，但不会替业务自动批准破坏性变更。

## 生成差异和迁移计划

~~~java
GraphSchemaInspection inspection = graph.manager().inspectSchema("neo4j");
GraphSchemaDiff diff = GraphSchemaComparator.compare(expected, inspection.getSchema());
GraphSchemaMigrationPlan plan =
    GraphSchemaMigrationPlanner.plan(expected, inspection.getSchema());
if (plan.requiresApproval()) {
    // 将删除或结构变化交给人工审批。
}
~~~

迁移风险分为 NONE、ADDITIVE、REVIEW_REQUIRED 和 DESTRUCTIVE。

## 推荐流程

1. 读取当前 Schema；
2. 与应用期望 Schema 比较；
3. 展示 additions、changes 和 removals；
4. 根据风险决定是否审批；
5. 调用 applySchemaResult 执行；
6. 保存 Schema 版本、执行人和结果；
7. 对关键查询执行回归验证。

## 增量属性

Nebula 的 CREATE TAG/EDGE IF NOT EXISTS 不会自动合并新属性。适配器会读取现有定义并执行 ALTER ADD，
随后等待 StorageD 的数据面探测成功后才返回。Neo4j 的 Portable Schema 主要创建约束和索引，属性结构更多
由实际数据和数据库规则决定。

## 不应自动执行的变化

- 删除节点标签或边类型；
- 删除仍被数据使用的属性；
- 改变已有属性类型；
- 修改唯一约束；
- 删除索引后立即执行大规模查询。

这些变化应由上层迁移系统提供备份、审批、灰度和回滚策略。


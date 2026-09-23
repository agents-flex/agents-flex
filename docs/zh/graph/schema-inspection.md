# Schema 反查

GraphManager.inspectSchema 用于读取后端当前可观察到的 Schema，返回 GraphSchemaInspection。

~~~java
GraphSchemaInspection inspection = graph.manager().inspectSchema("neo4j");
GraphSchema actual = inspection.getSchema();
for (String warning : inspection.getWarnings()) {
    System.out.println(warning);
}
~~~

## 反查结果

反查结果通常包含 Schema、是否完整、warning、不支持或无法推断的元数据以及读取时间。isComplete 为 false
时，不能把结果当作数据库的完整事实，应在迁移页面明确提示后端元数据不完整。

## 后端差异

Neo4j 可以读取节点标签、属性和部分索引信息，但关系端点标签不一定能从关系数据中可靠推断。

Nebula 可以读取 TAG 和 EDGE 定义，但当前 Portable Inspection 不返回完整的索引元数据和端点约束。

## 使用边界

反查适合用于启动前校验、Schema 迁移预览、控制面展示数据库当前结构，以及发现手工 DDL 和应用声明的差异。
反查不应直接覆盖应用声明，也不应把后端返回的未知类型静默转换为业务类型。


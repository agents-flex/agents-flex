# Schema 定义

GraphSchema 描述节点类型、边类型、属性和索引。Schema 是跨后端的声明模型，适配器负责将其转换为目标
数据库的 DDL。

## 节点和边类型

~~~java
GraphSchema schema = GraphSchema.builder()
    .nodeType(GraphSchema.NodeType.of("Person",
        new GraphSchema.Property("name", GraphSchema.PropertyType.STRING, false),
        new GraphSchema.Property("active", GraphSchema.PropertyType.BOOLEAN, false)))
    .edgeType(GraphSchema.EdgeType.any("KNOWS",
        new GraphSchema.Property("weight", GraphSchema.PropertyType.DOUBLE, false)))
    .build();
~~~

Neo4j 的关系端点标签可以表达更精确的约束；Nebula Portable Schema 当前只把边类型和边属性作为公共结构，
端点约束不一定能被完整表达。

## 属性类型和索引

支持 STRING、BOOLEAN、INT64、DOUBLE、DATE 和 DATETIME。属性名在同一个节点类型或边类型中必须唯一，
类型变化不应直接通过 ADDITIVE 模式强行覆盖。

~~~java
GraphSchema.Index index = new GraphSchema.Index(
    "person_name_index", GraphSchema.IndexTarget.NODE, "Person",
    Collections.singletonList("name"), false);
~~~

Nebula 属性过滤要求有效索引；创建索引后还需要完成索引重建和数据面传播。Neo4j 对普通索引和唯一约束的
支持更完整，但关系唯一约束仍然不属于 Portable 能力。

## SchemaMode

| 模式 | 说明 |
| --- | --- |
| VALIDATE_ONLY | 只验证，不执行 DDL |
| CREATE_IF_ABSENT | 只创建缺失定义 |
| ADDITIVE | 创建缺失定义并增加兼容属性 |

Schema 应在部署或独立迁移阶段执行。不要将不可逆删除放在每次应用启动的自动初始化中。

## 结果化应用

~~~java
GraphSchemaApplyResult result = graph.manager().applySchemaResult(
    "neo4j", schema, GraphManager.SchemaMode.ADDITIVE);
if (!result.isSuccess()) {
    throw result.getError();
}
~~~

结果对象可以向控制面提供应用步骤、warning、错误和耗时。


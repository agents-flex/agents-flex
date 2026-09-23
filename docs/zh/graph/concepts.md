# 核心概念

## Graph Store

GraphStore 是一个后端实例的统一入口，组合管理、写入、查询、事务、导入和健康检查能力。Store 通常对应
一个连接配置和一个默认 Graph Space。

## Graph Space

Graph Space 是 SDK 中的逻辑数据空间概念：

- Neo4j 适配器将其映射为 Neo4j database；
- Nebula 适配器将其映射为 Nebula Space。

一个 Store 可以通过 GraphOptions.space 路由到多个空间，但不同后端对动态切换空间的连接和权限要求不同。

## 节点和边

节点至少包含稳定 ID、一个或多个标签以及任意属性。边包含 source ID、edge type、target ID、rank 和属性。
边的身份不是 Java 对象地址，而是 sourceId、type、targetId、rank 的组合。

## Schema

Schema 描述节点类型、边类型、属性和索引。它是应用声明和数据库结构之间的契约，不等同于业务对象模型。
数据库可能只验证部分 Schema，适配器会在能力矩阵和反查结果中说明差异。

## Portable Query

Portable Query 是可以被多个适配器编译的统一查询，例如从某个标签开始、沿某种边方向遍历、对属性进行
比较并返回属性、实体、路径或聚合。Portable 不表示不同后端的执行计划和性能完全相同。

## Native Query

Native Query 是后端原生语句。它可以突破 Portable API 的能力范围，但会把代码绑定到具体数据库方言。

## Capability

Capability 是后端支持矩阵，不是静态文档标签。应用应在运行时读取它，用于隐藏不支持的操作、给出限制说明、
选择 Portable 或 Native 路径，以及在迁移页面展示风险。


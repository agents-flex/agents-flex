# 架构与核心概念

Graph 模块是 SDK 和后端适配器，不包含管理后台、图可视化组件、权限系统或数据导入文件解析器。上层应用
可以使用 SDK 提供的控制面和数据面契约自行开发这些能力。

## 分层结构

~~~text
业务应用 / 自定义控制面
              │
              ▼
        agents-flex-graph-api
              │
      ┌───────┼────────┬────────────┐
      ▼       ▼        ▼            ▼
   Manager  Writer   Query       Transaction
      │       │        │            │
      └───────┴────────┴────────────┘
              │
      Neo4j Adapter / Nebula Adapter
              │
        图数据库服务端
~~~

## 控制面和数据面

控制面负责图空间和结构：创建、删除、列出 Graph Space，应用和反查 Schema，创建索引，检查后端能力和执行
健康检查。

数据面负责数据读写：节点和边 upsert、删除，批量和异步导入，遍历、聚合、分页、原生查询以及事务中的读写。

GraphStore 只是组合入口，具体职责应依赖更小的接口。需要只查询数据的服务可以只依赖 GraphQueryExecutor，
不必依赖整个 Store。

## Portable API 和 Native API

Portable API 使用 TraversalQuery、GraphMutation 等公共模型表达跨后端语义。适配器负责将其编译成目标方言，
并在无法保持语义一致时抛出 UnsupportedGraphFeatureException。

Native API 使用 NativeGraphQuery 直接传入 Cypher 或 nGQL，适合最短路径、数据库专属函数、管理语句和执行计划
调试。Native API 不会自动把 Cypher 转换为 nGQL，也不保证两个后端的结果结构一致。

## 一次查询的生命周期

~~~text
构建 GraphQuery
      │
      ▼
校验标识符、投影和过滤条件
      │
      ▼
后端编译器生成 Cypher / nGQL
      │
      ▼
绑定参数并执行
      │
      ▼
转换为 GraphResult / GraphRecord
      │
      ▼
应用处理分页、截断和子图结果
~~~

值会作为参数绑定，标签、边类型、属性名和别名属于语句结构，必须使用合法标识符，不能把用户输入直接
拼接到这些位置。

## 能力声明和资源边界

后端在 GraphCapabilities 中声明支持的 GraphFeature、限制、模式和参数上限。能力不支持时，SDK 应明确失败，
而不是悄悄退化为全图扫描或改变写入语义。

SDK 负责统一 Java 抽象、方言编译、结果转换、进程内异步导入、错误码和结果元数据；不负责数据库部署、
备份、高可用、用户权限、业务文件解析、分布式任务调度、UI 或图探索器。


# 依赖与模块

Graph 模块按公共契约和后端实现拆分。应用只应引入实际使用的适配器，避免把所有数据库客户端都加入运行时。

## Maven 模块

| 模块 | 职责 |
| --- | --- |
| agents-flex-graph-api | 公共模型、接口、查询 DSL、Schema、导入和异常 |
| agents-flex-graph-extractor | 基于 DocumentSplitter 和 ChatModel 的知识抽取流程 |
| agents-flex-graph-neo4j | Neo4j Java Driver 适配器 |
| agents-flex-graph-nebula | Nebula Graph SessionPool 适配器 |

~~~xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-graph-extractor</artifactId>
    <version>\${VERSION}</version>
</dependency>

<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-graph-neo4j</artifactId>
    <version>\${VERSION}</version>
</dependency>
~~~

## 依赖边界

agents-flex-graph-api 不依赖 Neo4j 或 Nebula 客户端，因此可以在公共业务模块中使用接口和数据模型，
把具体适配器放在应用启动模块。

~~~text
业务模块
├── agents-flex-graph-api
└── agents-flex-graph-extractor（需要知识抽取时）

运行时模块
├── agents-flex-graph-neo4j
└── agents-flex-graph-nebula
~~~

同一个应用可以同时引入两个适配器，并通过不同的 GraphStore 实例访问不同后端。不要在业务代码中通过
instanceof 判断数据库类型；优先使用能力矩阵，确实需要专属语法时再隔离 Native Query。

## 版本管理

公共 API 兼容 Java 8。生产环境应固定 JDK、Graph SDK、数据库镜像、客户端驱动和 Schema 迁移版本。升级时
应重新执行真实集成测试，尤其是查询编译、属性类型、索引和事务行为。

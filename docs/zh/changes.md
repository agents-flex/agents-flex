# Agents-Flex ChangeLog

## v1.0.0-beta.2
- 新增：MilvusVectorStore 用于对 Milvus 向量数据库的支持，感谢 @xgc
- 新增：执行链的路由节点新增对 QLExpress 和 Groovy 的规则支持
- 优化：重构让 FunctionMessage 继承 AiMessage
- 优化：修改 VectorStore 的 document id 为 object 类型
- 优化：重命名 BaseDocumentLoader 为 StreamDocumentLoader
- 优化：优化 ExpressionAdaptor 方便用于对 Milvus 向量数据库的查询适配
- 优化：优化执行链 Chain 以及 Node 节点，方便更加容易的创建和配置
- 优化：重命名 BaseFunctionMessageParser 为 DefaultFunctionMessageParser
- 优化：修改 TextParser 为 JSONObjectParser
- 测试：多场景大量测试 Agent 以及 Chain，已初步具备编排能力
- 文档：https://agentsflex.com 官网上线
- 文档：完善基础文档

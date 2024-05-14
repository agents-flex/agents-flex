# Agents-Flex ChangeLog

## v1.0.0-beta.3

- Added: Support for Milvus vector database with MilvusVectorStore, thanks to @xgc
- Added: Rules support for QLExpress and Groovy added to routing nodes in execution chains
- Optimized: Refactored FunctionMessage to extend AiMessage
- Optimized: Modified document id in VectorStore to use object type
- Optimized: Renamed BaseDocumentLoader to StreamDocumentLoader
- Optimized: Enhanced ExpressionAdaptor for easier adaptation to Milvus vector database queries
- Optimized: Simplified creation and configuration of execution chains (Chain) and nodes (Node)
- Optimized: Renamed BaseFunctionMessageParser to DefaultFunctionMessageParser
- Optimized: Renamed TextParser to JSONObjectParser
- Testing: Extensive testing conducted on agents and chains in various scenarios, demonstrating preliminary orchestration capabilities
- Documentation: Official website launched at https://agentsflex.com
- Documentation: Basic documentation enhanced

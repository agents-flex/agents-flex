# Agents-Flex ChangeLog


## v1.0.0-beta.5 20240617
- feat: add ImagePrompt to send image to LLM
- feat: chatOptions add topP/topK and stop config
- refactor: rename TextMessage.java to AbstractTextMessage.java
- refactor: refactor llm methods
- refactor: refactor FunctionMessageResponse.java
- refactor: optimize HttpClient.java And SseClient.java
- fix: fix function calling error in QwenLLM
- test: add chat with image test


---
- 新增：新增 ImagePrompt 用于发送图片对话的场景
- 新增：对话模型下的 ChatOptions 添加 topK 和 topP 配置的支持
- 优化：重命名 TextMessage 为 AbstractTextMessage
- 优化：重构 LLM 的方法定义，使之更加简单易用
- 优化：优化 HttpClient.java 和 SseClient.java 的相关代码
- 修复：通义千问 QwenLLM 在 function calling 下无法正常调用的问题
- 测试：添加发送图片相关的测试内容





## v1.0.0-beta.4 20240531
- feat: OpenAiLlm support embedding model config
- feat: add get dimensions method in EmbeddingModel
- feat: SparkLlm support embedding
- feat: optimize MilvusVectorStore
- feat: MilvusVectorStore add username and password config
- refactor: optimize HttpClient.java
- refactor: optimize AliyunVectorStore
- refactor: update StoreOptions to extends Metadata
- refactor: optimize StoreResult and Metadata
- fix: fix AIMessage tokens parse


---
- 新增：OpenAiLlm 添加自定义 embedding 模型的支持
- 新增：EmbeddingModel 添加获取向量维度的支持
- 新增：SparkLlm 星火大模型添加对 embedding 的支持
- 新增：添加 Milvus 向量数据库的支持
- 优化：优化 HttpClient 的代码
- 优化：优化 AliyunVectorStore 的代码
- 优化：优化 StoreOptions 和 Metadata 的代码
- 修复：AIMessage 的 tokens 消耗解析不正确的问题




## v1.0.0-beta.3 20240516
- feat：add "description" to agent for automatic arrangement by LLM
- feat: StoreResult can return ids if document store success
- feat: StoreOptions support set multi partitionNames
- feat: add DocumentIdGenerator for Document and Store
- feat: add ChainException for Chain.executeForResult
- refactor: rename "SimplePrompt" to "TextPrompt"

---
- 新增：为 Agent 添加 description 属性，方便用于 AI 自动编排的场景
- 新增：Agent 添加对 outputKeys 的定义支持
- 新增：添加 DocumentIdGenerator 用于在对文档存储时自动生成 id 的功能
- 新增：StoreOptions 添加多个 partitionName 配置的支持
- 新增：当 Document 保存成功时，自动返回保存的 id
- 优化：Chain.executeForResult 会抛出异常 ChainException
- 修复：ChatGLM 的 Chat JSON 解析错误的问题
- 测试：优化 SparkLlmTest 的测试代码
- 文档：完善基础文档

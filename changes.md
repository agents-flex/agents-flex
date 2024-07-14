# Agents-Flex ChangeLog


## v1.0.0-beta.8 20240714
- feat: add "async" flag for the ChainNode
- feat: add Ollama LLM
- feat: add DnjsonClient for OllamaLlm
- refactor: refactor ChainCondition.java
- refactor: add throw LlmException if LLMs has error
- refactor: refactor DocumentParser
- refactor: refactor chain module
- refactor: rename GroovyExecNode.java and QLExpressExecNode.java
- refactor: add children property in Parameter
- refactor: remove unused code AsyncHttpClient.java
- refactor: use LlmException to replace LLMClientException
  fix: Milvus type mismatch for filed 'id'
- test: add LoopChain test
- test: add ollama test use openai instance
- docs: add Japanese README

---
- 新增：为 ChainNode 添加 "async" 属性标识的设置
- 新增：添加基于 Ollama 大语言模型的对接，非 openai 适配模式
- 新增：新增 DnjsonClient 用于和 Ollama 的 stream 模型对接
- 优化：重构 ChainCondition
- 优化：chat 时当大语言模型发生错误时抛出异常，之前返回 null
- 优化：重构 DocumentParser
- 优化：Parameter 支持子参数的配置能力
- 修复：Milvus 向量数据库当传入 number 类型是出错的问题
- 测试：添加对 LoopChain 的测试
- 测试：添加文使用 openai 兼容 api 对 Ollama 对接的测试




## v1.0.0-beta.7 20240705
- feat: add image models support
- feat: add SimpleTokenizeSplitter
- feat: add OmniParseDocumentParser
- feat: add openai，stability AI and gitee-sd3 AI support
- feat: add moonshot support
- feat: add chain dsl support
- refactor: optimize llm clients
- refactor: optimize SparkLLM
- refactor: optimize slf4j dependencies
- refactor: optimize Agent define
- refactor: optimize chain
- test: add .pdf and .doc parse test
- test: add SimpleDocumentSplitterTest.java

---
- 新增：新增图片模型的支持
- 新增：新增 SimpleTokenizeSplitter 分割器
- 新增：新增 OmniParseDocumentParser 文档解析器
- 新增：新增 openai、stability ai 以及 gitee ai 对图片生成的支持
- 新增：新增月之暗面的支持
- 优化：优化  llm 客户端的细节
- 优化：优化星火大模型的细节
- 优化：优化 slf4j 依赖的细节
- 优化：优化 Agent 和 Chain 的定义细节
- 测试：添加 .pdf 和 .doc 的解析测试
- 测试：添加文档分割器的测试
- 测试：添加 token 文档分割器的测试



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

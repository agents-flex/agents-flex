# Agents-Flex ChangeLog

## v1.0.0-rc.6 20250220
- feat: Springboot's automatic configuration class for Ollama
- feat: Added ToolPrompt function to facilitate the use with Function Call
- refactor: Change openAi to openAI
- refactor: Optimize LlmNode and TextPromptTemplate
- refactor: Upgrade related dependencies to the latest version
- refactor: Optimize the empty user prompt words defined during the LlmNode runtime
- refactor: Move the package name of functions to the directory llm (destructive update!!!)
- refactor: Refactor InputParameter and OutputKey to merge into Parameter (destructive update!!!)
- fix: Use the openai interface to connect to the local ollama to build a large model, and multiple function definitions are called abnormally
- fix: Fix the problem that agents-flex-bom cannot pull group code

---
- 新增：Springboot 对 Ollama 的自动配置类
- 新增：新增 ToolPrompt 功能，方便配合 Function Call 的使用
- 优化：修改 openAi 为 openAI
- 优化：优化 LlmNode 和 TextPromptTemplate
- 优化：升级相关依赖到最新版本
- 优化：优化  LlmNode 运行期定义空的用户提示词
- 优化：移动 functions 的包名到目录 llm（破坏性更新 !!!）
- 优化：重构 InputParameter 和 OutputKey 合并到 Parameter（破坏性更新 !!!）
- 修复：使用 openai 接口对接本地 ollama 搭建大模型，多个函数定义调用异常
- 修复：修复 agents-flex-bom 无法拉群代码的问题



## v1.0.0-rc.5 20250210
- feat: Added support for VectoRex vector database
- feat: Added support for DeepSeek large models
- feat: ImagePrompt adds support for local files, Stream and Base64 configurations
- refactor: agents-flex-bom facilitates one-click import of all modules

---
- 新增：添加 VectoRex 向量数据库的支持
- 新增：增加 DeepSeek 大模型的支持
- 新增：ImagePrompt 添加本地文件、Stream 和 Base64 配置的支持
- 优化：agents-flex-bom 方便一键导入所有模块



## v1.0.0-rc.4 20241230
- refactor: Use pom to build and only manage versions
- refactor: Optimize the relevant code of RedisVectorStore
- refactor: BaseNode.getChainParameters() method
- refactor: Optimize Chain.executeForResult method

---
- 优化: 采用 pom方式构建并只做版本统一管理
- 优化: 优化 RedisVectorStore 的相关代码
- 优化: BaseNode.getChainParameters() 方法
- 优化: 优化 Chain.executeForResult 方法



## v1.0.0-rc.3 20241126
- refactor: optimize Chain.executeForResult() method
- refactor: optimize Chain events
- fix: fixed Spark payload build error
- fix: fixed qwen model unable to embed

---
- 优化: 优化 Chain.executeForResult() 方法
- 优化: 优化 Chain 的相关 event 事件
- 修复: 修复星火大模型 payload 构建错误
- 修复: 修复 qwen 大模型无法使用 Embedding 的问题



## v1.0.0-rc.2 20241118
- feat: Gitee AI adds support for Function Calling
- feat: HumanMessage adds support for toolChoice configuration
- refactor: Optimize editing node BaseNode and Maps tool classes

---
- 新增: Gitee AI 添加对 Function Calling 的支持
- 新增: HumanMessage 添加 toolChoice 配置的支持
- 优化: 优化编辑节点 BaseNode 和 Maps 工具类



## v1.0.0-rc.1 20241106
- refactor: add BaseFunction.java
- fix: spark LLM can not support v4.0
- fix: fix code node can not get the parameters

---
- 优化：新增 BaseFunction 类
- 修复：修复星火大模型不支持 v4.0 的问题
- 修复：修复代码节点无法获取参数的问题



## v1.0.0-rc.0 20241104
- refactor: refactor llm apis
- refactor: refactor chain and nodes
- refactor: optimize agents-flex-solon-plugin @noear_admin

---
- 优化：重构 llm api
- 优化：重构 chain 链路 及其相关节点
- 优化：优化 agents-flex-solon-plugin @noear_admin



## v1.0.0-beta.13 20241026
- feat: add plugin for solon framework
- refactor: optimize VectorStore delete methods
- refactor: optimize RedisVectorStore for sort by desc
- refactor: optimize SparkLLM embedding

---
- 新增：添加 solon 添加新的插件支持
- 优化: 重构 VectorStore 的 delete 方法
- 优化: 优化 RedisVectorStore 的搜索排序
- 优化: 星火大模型新增秒级并发超过授权路数限制进行重试


## v1.0.0-beta.12 20241025
- refactor：add DocumentStoreConfig
- refactor：optimize HistoriesPrompt.java
- refactor: update pom.xml in agents-flex-bom
- refactor: upgrade jedis version to "5.2.0"
- refactor: optimize RedisVectorStore
- fix: NoClassDefFoundError in jdk17: javax/xml/bind/DatatypeConverter 感谢 @songyinyin #I9AELG

---
- 优化：添加 DocumentStoreConfig，向量数据库的配置都实现 DocumentStoreConfig
- 优化：重构优化 HistoriesPrompt，使其支持更多的属性配置
- 优化：更新 agents-flex-bom 的 pom.xml
- 优化：升级 jedis 版本为 "5.2.0"
- 优化：重构 RedisVectorStore 的错误信息，使之错误信息更加友好
- 修复：修复 jdk17 下出现 NoSuchMethodError 问题，感谢 @songyinyin #I9AELG



## v1.0.0-beta.11 20240918
- feat: GenerateImageRequest add negativePrompt property
- feat: Maps Util add putOrDefault method
- feat: add siliconFlow image models
- feat: ChatOptions add "seed" property
- feat: Maps can put a child map by key
- feat: Ollama add options config
- feat: Ollama function calling support
- feat: add StringUtil.isJsonObject method
- refactor: BaseImageRequest add extend options property
- refactor: make ImagePrompt to extends HumanMessage
- refactor: ImageResponse add error flag and errorMessage properties
- refactor: rename Image.writeBytesToFile to writeToFile
- refactor: rename "giteesd3" to "gitee"
- refactor: optimize VectorData.toString


---
- 新增：GenerateImageRequest 添加反向提示词相关属性
- 新增：Maps 工具类添加 putOrDefault 方法
- 新增：添加 siliconFlow 的图片模型的支持
- 新增: ChatOptions 添加 "seed" 属性
- 新增：Maps 可以 put 一个子 map 的功能
- 新增：新增 Ollama 的函数调用（Function Calling）的支持
- 新增：添加 StringUtil.isJsonObject 方法
- 优化：重构 BaseImageRequest 类，添加 options 属性
- 优化：重构 ImagePrompt 使之继承于 HumanMessage
- 优化：重构 ImageResponse 类，添加 error 和 errorMessage 属性
- 优化：修改 Image.writeBytesToFile 方法为 writeToFile
- 优化：重命名 "giteesd3" 为 "gitee"
- 优化：重构 VectorData.toString 方法




## v1.0.0-beta.10 20240909
- feat: Added support for RedisStore vector storage, thanks to @giteeClass
- feat: Added support for large model dialogues for Coze Bot, thanks to @yulongsheng
- feat: Automatic configuration of Springboot for ElasticSearch Store, thanks to @songyinyin
- feat: Added support for Embedding of Tongyi Qianwen, thanks to @sssllg
- feat: Added support for all text generation models of Gitee AI's serverless
- feat: Added support for all image generation models of Gitee AI's serverless
- docs: Corrected sample code errors in the documentation

---
- 新增：添加 RedisStore 的向量存储支持，感谢 @giteeClass
- 新增：新增 Coze Bot 的大模型对话支持，感谢 @yulongsheng
- 新增: ElasticSearch Store 对 Springboot 的自动配置功能，感谢@songyinyin
- 新增：新增通义千问的 Embedding 支持，感谢 @sssllg
- 新增：新增对 Gitee AI 的 serverless 所有文本生成模型的支持
- 新增：新增对 Gitee AI 的 serverless 所有图片生成模型的支持
- 文档：修正文档的示例代码错误



## v1.0.0-beta.9 20240813
- feat: add custom request header in openaiLLM https://github.com/agents-flex/agents-flex/issues/5
- feat: add https.proxyHost config for the http client, close https://github.com/agents-flex/agents-flex/issues/1
- feat: add SpringBoot3 auto config support @songyinyin
- feat: add openSearch store support @songyinyin
- fix: fix config error in QwenAutoConfiguration @songyinyin
- fix: NPE in OpenAILLmUtil.promptToEmbeddingsPayload
- fix: fix FunctionMessageResponse error in BaseLlmClientListener, @imayou
- refactor: update bom module
- refactor: optimize SparkLlm.java

---
- 新增: 添加自定义 openaiLLM 请求 api 的支持  https://github.com/agents-flex/agents-flex/issues/5
- 新增: 添加 https.proxyHost 配置的支持 https://github.com/agents-flex/agents-flex/issues/1
- 新增: 添加对 SpringBoot3 自动配置的支持 @songyinyin
- 新增: 添加使用 openSearch 用于向量数据存储的支持 @songyinyin
- 修复: 修复 QwenAutoConfiguration 配置错误的问题  @songyinyin
- 修复: 修复 OpenAILLmUtil.promptToEmbeddingsPayload 空指针异常的问题
- 修复: 修复 FunctionMessageResponse 在某些情况下出错的问题, @imayou
- 优化: 更新重构 bom 模块
- 优化: 优化 SparkLlm.java 的相关代码



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
- feat: OpenAILlm support embedding model config
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
- 新增：OpenAILlm 添加自定义 embedding 模型的支持
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
- 新增：Agent 添加对 outputDefs 的定义支持
- 新增：添加 DocumentIdGenerator 用于在对文档存储时自动生成 id 的功能
- 新增：StoreOptions 添加多个 partitionName 配置的支持
- 新增：当 Document 保存成功时，自动返回保存的 id
- 优化：Chain.executeForResult 会抛出异常 ChainException
- 修复：ChatGLM 的 Chat JSON 解析错误的问题
- 测试：优化 SparkLlmTest 的测试代码
- 文档：完善基础文档

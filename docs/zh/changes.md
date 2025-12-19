# Agents-Flex ChangeLog

## v2.0.0-beta.5 20251219
- test(openai): remove unnecessary thread sleep in chat model tests
- feat(chat): add includeUsage option for streaming responses
- feat(openai): add stream chat test for bailian model
- feat(core): add stopped flag and improve stream listener logic
- fix(stream): handle final delta correctly in stream client listener and Wrapped final message notification in try-finally block
- feat(openai): add support for extra options in chat request
- docs(models): update embedding model translation



## v2.0.0-beta.4 20251210
- fix(core):优化AI消息增量更新的空值判断
- fix(core):修复工具调用合并逻辑
- chore(deps): 移除嵌入模型相关依赖配置
- fix(message):优化 ToolCall 参数解析逻辑
- feat(core):优化聊天模型和客户端实现
- fix: embedding module packaging
- feat(bom): 添加嵌入模型依赖项


## v2.0.0-beta.2 20251202
- 修复：修复 Maven 依赖异常，更新版本号，感谢 @jieven

## v2.0.0-beta.1 20251126
- 移除 Chain 模块，合并到 Tinyflow
- 移除 documentParser 模块
- 移除 DocumentLoader 模块
- 新增 file2text 模块
- 新增 observability 模块
- 新增 RAG 执行 AI 对文档进行拆分
- 修改 LLM 为 ChatModel
- 修改 LLMClient 为 StreamClient
- 修改 LLMClientListener 为 StreamClientListener
- 修改 LLMConfig 为 ChatConfig
- 修改 Function 为 Tool
- 重命名 PromptFormat 为 MessageFormat
- 重命名 TextPromptTemplate 为 PromptTemplate
- 重命名 TextPrompt 为 SimplePrompt
- 重命名 HistoriesPrompt 为 MemoryPrompt
- 重命名 HumanMessage 等合并为 UserMessage


## v1.4.2 20251117
- 新增：新增本地 Token 计算的相关内容，方便在某些情况下无法获取外部 token 时使用
- 新增: 新增 file2text 模块
- 新增: Chain 支持主动去 暂停执行 或 恢复执行
- 新增：feat: 新增可观测的相关能力
- 优化：重构移动 ReAct 的包到 agents 目录下
- 优化：优化 Chain.addEdge 自动为没有 id 的 edge 添加 id
- 优化：Chain.addNode 自动为 node 添加 inwardEdges 和 outwardEdges
- 优化：优化 ChainHolder 的属性类型定义，方便准确序列化
- 优化：ChainEdge 移除无用的定义



## v1.4.1 20251023
- 新增：prompt 格式化新增 `{{ aa ?? bb ?? 'default'}}` 取值语法
- 优化：优化 chain.get 方法获取数据的性能
- 优化：移除 gitee 旧的 api 支持（官方已下架）


## v1.4.0 20251020
- 修复： computeCost 可能等于 null 而出现 NPE 的问题


## v1.3.9 20251019
- 新增: 添加 Chain 关于算力消耗配置的功能
- 新增: Chain 添加 loopNodeExecutionLimit 配置，保护由于用户配置不当可能造成死循环执行的问题
- 新增: 优化监听器，当多个监听器发生时，有监听器发生错误不影响其他监听器执行
- 新增: Node 支持配置积分消耗的计算逻辑和表达式
- 新增: Chain 的取值支持三目运算
- 修复:避免截断后添加 systemMessage 时导致 memory 中的 systemMessage 越来越多


## v1.3.8 20251010
- 新增：图片消息添加多张图片的支持
- 新增：大模型节点添加图片识别的能力
- 新增：大模型节点支持多种 json 格式输出
- 优化：优化 okHttpclient 的请求机制，更轻量化
- 优化：优化 OkHttpClientUtil 的配置支持环境变量
- 优化：ImageUtil 支持自动添加 base64 url 前缀


## v1.3.7 20250929
- 新增：支持 Chroma 向量数据库（1.1.10），目前只支持单租户，单数据库（有默认且可配置），感谢 @joe.wen
- 新增：TextPromptTemplate.formatToString 添加 escapeJson 参数配置的支持


## v1.3.6 20250924
- 修复：ConfirmNode 必须勾选必填才能正确进行数据确认的问题


## v1.3.5 20250917
- 新增: 新增 ChainNodeValidator 用于给 Node 自定义其验证器
- 新增: 新增 ChainNode.validate 方法，用于校验其数据是否完善
- 新增: 工作流执行新增节点算力消耗的定义
- 新增: getParameterValues 通过 LinkedHashMap 返回属性，支持参数顺序
- 优化：优化 HumanMessage 类的函数处理逻辑
- 优化: 重构 Chain 的验证逻辑
- 修复: 修复 Parameter 为非必填，但是也必须输入参数的问题
- 修复: 在 js 执行节点可能出现 java.lang.NoClassDefFoundError: com/ibm/icu/number/Notation 错误的问题
- 修复：正则表达式 `Action Input: (\{.<em>?})` 在匹配时会漏掉最后一个 `"}"` 的问题，感谢 @狂野流星


## v1.3.4 20250817
- 新增：Parameter 新增 formPlaceholder 属性


## v1.3.3 20250806
- 优化：优化合并 ConfirmParameter 到 Parameter，以支持更多的场景


## v1.3.2 20250731
- 优化：优化 AiMessageResponse.getFunctionCallers 方法
- 修复：多轮 tool call 时,获取最后一条 HumanMessage 错误的问题
- 修复：Chain 的 Parameter 类型为 Array 时，内容固定值无法解析的问题


## v1.3.1 20250722
- 新增：ChatOptions 新增 extra 配置，用于自定义大模型的参数内容
- 修复：节点异步执行的情况下，可能出现 check 不正确的问题
- 修复：jsExecNode 无法转换结果为 JsonObject 的问题
- 优化：优化 getNodeContext 方法，只需要传入 id 值
- 优化：移除 Moonshot ，使用 openai 替代


## v1.2.9 20250718
- 优化：优化 parent 和 children 的过度设计，使之逻辑更加简洁
- 修复：修复 Node 包含子的 chain 时，会导致 json 解析错误的问题


## v1.2.8 20250715
- 新增: ReActAgent 添加 ChatOptions 的设置能力
- 新增: ConfirmParameter 添加更多的配置参数支持
- 优化: 优化 getParameterValues 的错误信息
- 优化: 移除 chain 非必要的 error 日志
- 优化: 优化 Chain.getParameterValues


## v1.2.7 20250713
- 新增：代码执行节点添加 “_context” 对象
- 新增：新增 Chain.toJSON() 和 Chain.fromJSON() 方法
- 新增：新增 ConfirmNode 用于支持用户手动确认的场景
- 优化：优化 Chain.getParameterValues 方法
- 优化：移动 Chain 监听器的相关类到 listener 包里去


## v1.2.6 20250709
- 新增: Chain 新增 getNodeExecuteResult 方法
- 优化：优化重构 EdgeCondition 和 NodeCondition 方法


## v1.2.5 20250707
- 新增：新增 Audio 多模态的支持
- 新增：新增 ReActMessageBuilder，允许用户构建自定义的消息
- 优化：feat: 修改 TextAndImageMessage 和 TextAndAudioMessage 为 HumanImageMessage 和 HumanAudioMessage
- 修复：DeepseekLlm 无法自动注入，配置 factories 文件


## v1.2.4 20250701
- 新增：在节点执行出错时，添加必要的错误日志输出
- 新增：大模型 Parameter 添加子 Parameter 的配置支持
- 新增：ReActAgent 添加 continueOnActionJsonParseError 和 continueOnActionInvokeError 配置
- 修复：修复 EmbeddingModel.dimensions() 错误信息不友好的问题
- 修复：tool call第二次请求模型时缺少了tools信息 close #ICG584


## v1.2.3 20250626
- 新增：节点添加循环执行的配置能力
- 新增：新增 starter 中 deepseek 的配置支持，openai 可以指定 chatpath 属性
- 新增：OpenAILlm 支持自定义 HttpClient
- 优化：优化 bom 模块依赖冲突 close #ICG2TD
- 优化：优化 ReActAgent 代码，新增更多的监听支持
- 优化：优化 OkHttpClientUtil 的默认参数
- 优化：重命名 JavascriptStringCondition 为 JsCodeCondition
- 修复：修复 Gitee 生成图片错误的问题
- 文档：更新 chain 的相关文档
- 文档：更新优化 LLM 示例代码
- 文档：优化 prompt 示例代码
- 文档：节点循环示例代码


## v1.2.2 20250618
- 新增：新增 es 和 lucene 搜索引擎


## v1.2.0 20250614
- 新增：新增 "default" rerank 模型，用于对接多个不同的 rerank 服务
- 新增：新增 ReAct Agent
- 优化：挂起-恢复执行逻辑优化，每次执行节点移除挂起节点列表中该节点


## v1.1.8 20250605
- 新增：阿里云增加余弦相似度得分回显
- 修复: 修正 Milvus 下 COSINE 相似度计算方法



## v1.1.7 20250604
- 修复：使用 qwen-plus 调用 function_call 没有正确拼接大模型返回的参数问题
- 修复: 修复 DeepseekLlmUtil 类型转换错误
- 修复: HistoriesPrompt 的 toMessages 可能多次添加 systemMessage 的问题



## v1.1.5 20250603
- 修复：修复 CodeNode 的 js 无法通过 "." 调用 map 数据的问题



## v1.1.4 20250530
- 新增: 为 ChainStartEvent 和 ChainResumeEvent 添加获取初始化参数的功能
- 优化: 优化 JsExecNode 在每次执行脚本时新建一个独立 Context
- 优化: 优化 Event 的 toString
- 修复： node 的状态在执行时未自动变化的问题



## v1.1.3 20250527
- 修复：修复阿里云百炼 text-embedding-v3 向量化使用 milvus 使用默认向量模型导致两次维度不一致问题
- 修复：qwen3 非流式返回设置 enable_thinking 为 false



## v1.1.2 20250524
- 新增: StreamResponseListener 添加 onMatchedFunction 方法
- 新增: 添加 openai 兼容 api 的其他第三方 api 测试
- 优化: 添加 FunctionPrompt 的 toString 方法
- 优化: 优化 ImagePrompt 的方法
- 优化: 优化 ToolPrompt 支持多个方法调用
- 优化: 优化 Stream 模型下的 Function Call
- 优化: 优化 SseClient 的 tryToStop 方法
- 优化: 优化 FunctionCall 以及添加 toString 方法
- 优化: 优化 OpenAILlm.java



## v1.1.1 20250522
- 新增：新增 NodeErrorListener 用于监听 node 的错误情况
- 优化：重构 ChainErrorListener 的参数顺序
- 优化：优化 getParameterValues 的默认值获取



## v1.1.0 20250516
- 优化：增强 LLM 的 markdown 包裹优化
- 优化：重命名 StringUtil.obtainFirstHasText 方法名称为  getFirstWithText
- 修复：修复大模型节点，返回 json 内容时不正确的问题
- 修复：修复 EndNode 在输出固定值时出现 NPE 的问题



## v1.0.9 20250513
- 新增: Chain 添加 reset 方法，使之调用后可以执行多次
- 优化：不允许设置默认 EmbeddingOptions 配置的 encodingFormat
- 优化：修改模型思考过程的设置，让 content 和 reasoningContent 输出内容一致，感谢 @Alex



## v1.0.8 20250511
- 优化：优化 elasticSearch 用户自定义集合名称就用用户自定义集合，没有传就用默认集合名称
- 优化：从命名 TextPromptTemplate.create 方法名称为 TextPromptTemplate.of，更加符合 “缓存” 的特征
- 修复：修复 openSearch 存储报错问题
- 文档：添加提示词相关文档
- 文档：添加 “模板缓存” 的相关文档
- 测试：添加 milvus 向量存储用法示例测试类，感谢 @lyg



## v1.0.7 20250508
- 新增: 添加 Milvus 的相识度返回
- 新增: Chain.getParameterValues 添加对固定数据格式填充的能力
- 优化: Parameter 添加 dataType 默认数据
- 优化: TextPromptTemplate.create 添加缓存以提高性能



## v1.0.6 20250507
- 新增: 增加 qdrant 向量数据库支持
- 优化: 重构 TextPromptTemplate，使其支持更多的语法
- 优化: 优化 pom 管理



## v1.0.5 20250430
- 新增: 允许通过 ChatOptions 在运行时动态替换模型名称
- 新增：增加是否开启思考模式参数，适用于 Qwen3 模型
- 新增：Document 增加文档标题
- 新增：增强知识库查询条件
- 新增：优化 Chain 的 get 逻辑，支持获取对象的属性内容
- 测试：添加通过 OpenAI 的 API 调用 Gitee 进行图片识别
- 测试：添加 chain 的数据获取测试



## v1.0.4 20250427
- 新增: 为 VectorData 添加 score 属性，统一文档 score 字段
- 优化：重构 Chain 的异步执行逻辑



## v1.0.3 20250425
- 新增: deepseek-r1 推理过程增量输出改为完整输出和内容的输出保持一致，感谢 @liutf
- 新增: 增加 QwenChatOptions，让通义千问支持更多的参数，感谢 @liutf
- 新增：新增 ChainHolder，用于序列化 ChainNode，以支持分布式执行
- 优化：优化 Chain，在暂停时抛出异常



## v1.0.2 20250412
- feat: add JavascriptStringCondition
- refactor: move "description" property to ChainNode
- test: add ChainConditionStringTest

---
- 新增：添加 JavascriptStringCondition 条件
- 重构：移动 "description" 属性到 ChainNode
- 测试：添加 ChainConditionStringTest 测试



## v1.0.1 20250411
- fix: LlmNode can not return the correct result if outType is text
- fix: TextPromptTemplate can not parse `{{}}`

---
- 修复：修复 LlmNode 当配置 outType 时，不能返回正确结果的问题
- 修复：TextPromptTemplate 无法可以解析 `{{}}` 的问题



## v1.0.0 20250407
- fix: fixed NodeContext.isUpstreamFullyExecuted() method
- feat: add "concurrencyLimitSleepMillis" config for SparkLlm
- feat: openai add chatPath config and embed path config
- feat: HistoriesPrompt add temporaryMessages config

---
- 修复：NodeContext.isUpstreamFullyExecuted() 方法判断错误的问题
- 新增: SparkLlm 添加 concurrencyLimitSleepMillis 配置
- 新增: openai 添加 chatPath 配置和 embed path 配置
- 新增: HistoriesPrompt 添加 temporaryMessages 配置



## v1.0.0-rc.9 20250331
- feat: Added support for vector database Pgvector, thanks @daxian1218
- feat: Chain added "SUSPEND" state and ChainSuspendListener listener
- feat: Chain's RefType added "fixed" type.
- feat: Chain's Parameter added "defaultValue"
- feat: Chain added ChainResumeEvent event
- feat: ChainNode added "awaitAsyncResult" property configuration
- refactor: Return the complete response and answer information of coze chat to obtain complete information such as conversation_id, thanks @knowpigxia
- refactor: Optimize the implementation details of RedisVectorStore
- refactor: Chain removed OnErrorEvent and added ChainErrorListener instead
- refactor: Rename BaseNode's "getParameters" method to "getParameterValues"
- refactor: Rename Chain's event and remove the On prefix

---
- 新增：新增向量数据库 Pgvector 的支持，感谢 @daxian1218
- 新增：Chain 新增 "SUSPEND" 状态以及 ChainSuspendListener 监听
- 新增：Chain 的 RefType 新增  "fixed" 类型。
- 新增：Chain 的 Parameter 新增 "defaultValue"
- 新增：Chain 新增 ChainResumeEvent 事件
- 新增：ChainNode 新增  "awaitAsyncResult" 属性配置
- 优化：返回 coze chat 完整的 response、answer 信息，以便获取 conversation_id 等完整信息，感谢 @knowpigxia
- 优化：优化 RedisVectorStore 的实现细节
- 优化：Chain 移除 OnErrorEvent 并新增 ChainErrorListener 代替
- 优化：重命名 BaseNode 的 "getParameters" 方法为 "getParameterValues"
- 优化：重命名 Chain 的事件，移除 On 前缀



## v1.0.0-rc.8 20250318
- feat: Added LLM support for siliconflow, thanks @daxian1218
- feat: Chain's dynamic code node supports running Javascript scripts, thanks @hhongda
- feat: Removed deepseek's invalid dependency on openai module, thanks @daxian1218
- feat: Optimized EmbeddingModel and added direct embedding of String

---
- 新增：新增 LLM 对 siliconflow（硅基流动）的支持，感谢 @daxian1218
- 新增：Chain 的动态代码节点支持运行 Javascript 脚本，感谢 @hhongda
- 优化：移除 deepseek 无效的依赖 openai 模块，感谢 @daxian1218
- 优化：优化 EmbeddingModel，添加直接对 String 的 embed



## v1.0.0-rc.7 20250312
- feat: Added the tool of adding reasoning content to the return message, supporting deepseek's reasoning return, thanks @rirch
- feat: Added support for vectorexdb embedded version, no need to deploy database separately, thanks @javpower
- feat: Added support for accessing Tencent's large model language, Wensheng graph model and vectorization interface, thanks @sunchanghuilinqing
- feat: Support for docking Doubao doubao-1-5-vision-pro-32k multimodal model and Wensheng graph, thanks @wang110wyy
- feat: Added Wensheng graph model of Alibaba Bailian platform, thanks @sunchanghuilinqing
- feat: Added VLLM-based large model access, thanks @sunchanghuilinqing
- feat: Added LogUtil for log output
- feat: Optimized the relevant code logic of DnjsonClient
- fix: The problem of too long uid of Spark large model, thanks @wu-zhihao
- fix: ChatStream of Ollama Llm An error occurred when actively closing the stream
- fix: Fixed an issue where the endpoint configuration of OllamaProperties was incorrect by default

---
- 新增：添加在在返回消息中增加推理内容的功能，支持 deepseek 的推理返回，感谢 @rirch
- 新增：添加 vectorexdb 内嵌版本支持，无需额外部署数据库，感谢 @javpower
- 新增：添加接入腾讯大模型语言、文生图模型与向量化接口的支持，感谢 @sunchanghuilinqing
- 新增：对接豆包 doubao-1-5-vision-pro-32k 多模态模型以及文生图的支持，感谢 @wang110wyy
- 新增：新增阿里百炼平台的文生图模型，感谢 @sunchanghuilinqing
- 新增：新增基于 VLLM 部署大模型接入，感谢 @sunchanghuilinqing
- 新增：新增 LogUtil 用于输出日志
- 优化：优化 DnjsonClient 的相关代码逻辑
- 修复：星火大模型的 uid 太长的问题，感谢 @wu-zhihao
- 修复：Ollama Llm 的 chatStream 主动关闭流时发生错误的问题
- 修复：修复默认情况下 OllamaProperties 的 endpoint 配置错误的问题



## v1.0.0-rc.6 20250220
- feat: Springboot's automatic configuration class for Ollama
- feat: Added ToolPrompt tool to facilitate the use with Function Call
- refactor: Change openAi to openAI
- refactor: Optimize LlmNode and TextPromptTemplate
- refactor: Upgrade related dependencies to the latest version
- refactor: Optimize the empty user prompt words defined during the LlmNode runtime
- refactor: Move the package name of tools to the directory chatModel (destructive update!!!)
- refactor: Refactor InputParameter and OutputKey to merge into Parameter (destructive update!!!)
- fix: Use the openai interface to connect to the local ollama to build a large model, and multiple tool definitions are called abnormally
- fix: Fix the problem that agents-flex-bom cannot pull group code

---
- 新增：Springboot 对 Ollama 的自动配置类
- 新增：新增 ToolPrompt 功能，方便配合 Function Call 的使用
- 优化：修改 openAi 为 openAI
- 优化：优化 LlmNode 和 TextPromptTemplate
- 优化：升级相关依赖到最新版本
- 优化：优化  LlmNode 运行期定义空的用户提示词
- 优化：移动 tools 的包名到目录 chatModel（破坏性更新 !!!）
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
- refactor: refactor chatModel apis
- refactor: refactor chain and nodes
- refactor: optimize agents-flex-solon-plugin @noear_admin

---
- 优化：重构 chatModel api
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
- feat: Ollama tool calling support
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
- refactor: optimize chatModel clients
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
- 优化：优化  chatModel 客户端的细节
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
- refactor: refactor chatModel methods
- refactor: refactor FunctionMessageResponse.java
- refactor: optimize HttpClient.java And SseClient.java
- fix: fix tool calling error in QwenLLM
- test: add chat with image test


---
- 新增：新增 ImagePrompt 用于发送图片对话的场景
- 新增：对话模型下的 ChatOptions 添加 topK 和 topP 配置的支持
- 优化：重命名 TextMessage 为 AbstractTextMessage
- 优化：重构 LLM 的方法定义，使之更加简单易用
- 优化：优化 HttpClient.java 和 SseClient.java 的相关代码
- 修复：通义千问 QwenLLM 在 tool calling 下无法正常调用的问题
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

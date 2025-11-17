# 核心概念

本节介绍 Agents-Flex 框架使用的核心概念。建议仔细阅读，以便了解框架概念和逻辑。

## 大语言模型（LLM）

大语言模型（Large Language Model, LLM）是 AI 应用开发的核心技术之一。它是一种基于深度学习的自然语言处理模型，能够理解和生成人类语言。LLM
通过海量文本数据训练，具备强大的上下文理解能力、多语言支持和广泛的领域知识。在 Agents-Flex 框架中，LLM
是驱动智能对话、文本生成和其他任务的基础引擎。

**关键点：**

- LLM 的能力范围包括问答、翻译、摘要生成等。
- 在框架中，LLM 被用作核心推理组件。
- 支持多种开源和闭源的 LLM 集成。

**示例代码：**

```java
Llm chatModel = OpenAILlm.of("sk-rts5NF6n*******");
String response = chatModel.chat("what is your name?");

System.out.println(response);
```

## 提示词（Prompt）

提示词（Prompt）是指用户或系统向大语言模型提供的输入文本。它是与 LLM 交互的主要方式，用于引导模型生成期望的输出。提示词的设计直接影响模型的响应质量和任务完成效果。

**关键点：**

- 提示词可以是问题、指令或上下文信息。
- 设计良好的提示词能够显著提升模型的性能。
- 在 Agents-Flex 中，提示词是任务执行的起点。

面对不同的场景，Agents-Flex 提供了多种提示词的实现。

- TextPrompt: 用于简单文本的提示词。
- FunctionPrompt: 用于函数调用的提示词。
- ImagePrompt: 用于图像的提示词。
- HistoriesPrompt: 用于历史对话的提示词。
- ToolPrompt: 用于调用工具确认的提示词。

**示例代码（简单调用）：**

```java 3
Llm chatModel = OpenAILlm.of("sk-rts5NF6n*******");

TextPrompt prompt = new TextPrompt("what is your name?");
String response = chatModel.chat(prompt);

System.out.println(response);
```

**示例代码（Function Calling）：**

```java 3-5
OpenAILlm chatModel = new OpenAILlm.of("sk-rts5NF6n*******");

FunctionPrompt prompt = new FunctionPrompt(
    "今天北京的天气怎么样"
    , WeatherUtil.class);

AiMessageResponse response = chatModel.chat(prompt);
System.out.println(response.callFunctions());
```

## 提示词模板（Prompt Template）

提示词模板（Prompt Template）是一种结构化的提示词设计方法。它通过预定义的模板格式，将动态变量插入到固定文本框架中，从而实现灵活且高效的提示词生成。提示词模板在批处理任务和多场景应用中尤为重要。

**关键点：**

- 模板通常包含占位符，用于动态替换用户输入或系统参数。
- 提高了提示词设计的可复用性和一致性。
- Agents-Flex 提供了内置的模板管理工具，简化开发流程。

**示例代码：**

```java
TextPromptTemplate promptTemplate = TextPromptTemplate.create(
    "你好，{name}  今天是:{x}"
);

Map<String ,Object> map = new HashMap<>();
map.put("name","michael");
map.put("x","星期五");

System.out.println(promptTemplate.format(map));
// 你好，michael  今天是:星期五
```

## 嵌入（Embedding）


嵌入（Embedding）是将文本、图像或其他数据转换为向量浮点数据的技术。这些向量浮点数据捕捉文本、图像和视频的含义，泛应用于搜索、推荐和分类任务中。Embedding 数组的长度称为向量的维度。

**关键点：**

- 在 Agents-Flex 中，嵌入（Embedding）常用于语义检索和上下文理解。
- 支持多种嵌入模型集成。

**示例代码：**

```java
Llm chatModel = OpenAILlm.of("sk-rts5NF6n*******");
VectorData embeddings = chatModel.embed("some document text");
System.out.println(Arrays.toString(embeddings.getVector()));
```


## 向量数据库（Vector Database）

向量数据库（Vector Database）是一种专门存储和检索高维向量的数据库系统。它优化了基于嵌入的相似性搜索，能够在大规模数据集中快速找到最相关的条目。矢量数据库是实现高效语义检索的关键组件。

**关键点：**

- 常见的矢量数据库包括 Milvus、ElasticSearch 和 Redis。
- 在 RAG（检索增强生成）场景中，矢量数据库用于存储文档嵌入。
- Agents-Flex 提供了与主流矢量数据库的无缝集成。

**示例代码：**

```java
RedisVectorStoreConfig config = new RedisVectorStoreConfig();
config.setUri("redis://localhost:6379");
config.setDefaultCollectionName("test05");

// 创建 RedisVectorStore 实例
DocumentStore store = new RedisVectorStore(config);

// 为 RedisVectorStore 设置嵌入模型
Llm chatModel = OpenAILlm.of("sk-rts5NF6n*******");
store.setEmbeddingModel(chatModel);


Document document = new Document();
document.setContent("你好");
document.setId(1);

// 将文档存储到 RedisVectorStore 中
store.store(document);


SearchWrapper sw = new SearchWrapper();
sw.setText("你好");

// 搜索 RedisVectorStore 中的文档
List<Document> search = store.search(sw);
System.out.println(search);

// 删除 RedisVectorStore 中的文档
StoreResult result = store.delete("1");
```



## 函数调用（Function Calling）

函数调用（Function Calling）是指大语言模型根据用户需求调用外部工具或 API 的能力。这种机制扩展了 LLM
的功能范围，使其能够执行复杂的任务，如查询数据库、调用天气服务或操作文件系统。

**关键点：**

- 函数调用增强了 LLM 的实用性和灵活性。
- Agents-Flex 支持自定义函数注册和调用逻辑。
- 开发者可以通过函数调用实现特定领域的定制化功能。


## Token

Token 是自然语言处理中的基本单位，通常是一个单词、标点符号或子词片段。LLM 的输入和输出均以 Token 为单位进行处理。Token
的数量直接影响计算成本和模型性能。

**关键点：**

- 不同模型对 Token 的定义和处理方式可能有所不同。
- 在大模型中，Token 往往和 “金钱” 是相等的，在大模型中，Token 消耗越多，由此产生的费用也就越多。
- 在 Agents-Flex 中，开发者需要关注 Token 的使用效率和限制。

> 在英语中，一个 Token 大约对应一个单词的 75%, 《莎士比亚的全集》总共约 90 万个单词，翻译过来大约有 120 万个 Token。

## 检索增强生成（RAG）

检索增强生成（Retrieval-Augmented Generation, RAG）是一种结合检索和生成的方法。它通过从外部知识库中检索相关信息，增强大语言模型的生成能力，从而提高输出的准确性和可靠性。

**关键点：**

- RAG 在问答系统、知识库查询等场景中表现优异。
- 在 Agents-Flex 中，RAG 流程通常包括嵌入生成、矢量检索和上下文融合。
- 通过 RAG，模型能够动态访问最新的外部信息。


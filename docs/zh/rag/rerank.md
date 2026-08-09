# Rerank 模型
<div v-pre>


## 1. 概念介绍

### 1.1 什么是 RerankModel

`RerankModel` 是一种用于文档重排序的模型接口，其主要作用是对已有的文档列表根据查询（query）进行相关性打分，并返回一个按相关性排序后的文档列表。

* 输入：查询文本 `query` + 文档列表 `documents`
* 输出：按相关性重新排序的文档列表，每个文档包含 `score`

在信息检索（IR）、语义搜索和问答系统中，Rerank 模型常用于在向量检索或初步检索后的候选文档上进行精细排序，从而提升检索准确率。



## 2. 快速入门


### 2.1 初始化 RerankModel


```java
import com.agentsflex.rerank.DefaultRerankModel;
import com.agentsflex.rerank.DefaultRerankModelConfig;
import com.agentsflex.core.document.Document;
import com.agentsflex.core.model.rerank.RerankOptions;

import java.util.Arrays;
import java.util.List;

public class RerankExample {

    public static void main(String[] args) {
        // 配置模型
        DefaultRerankModelConfig config = new DefaultRerankModelConfig();
        config.setEndpoint("https://api.example.com");
        config.setApiKey("your_api_key");

        // 初始化模型
        DefaultRerankModel rerankModel = new DefaultRerankModel(config);

        // 准备文档
        List<Document> docs = Arrays.asList(
            Document.of("Python read CSV using pandas"),
            Document.of("Read CSV with numpy.loadtxt()"),
            Document.of("Write JSON files in Python"),
            Document.of("CSV file format explained")
        );

        // 查询文本
        String query = "How to read CSV files in Python?";

        // 使用默认模型重排
        List<Document> rankedDocs = rerankModel.rerank(query, docs);

        // 输出结果
        rankedDocs.forEach(doc -> {
            System.out.println(doc.getContent() + " | score: " + doc.getScore());
        });
    }
}
```

### 2.3 输出示例

```
Python read CSV using pandas | score: 0.9565
CSV file format explained     | score: 0.8223
Read CSV with numpy.loadtxt() | score: 0.3108
Write JSON files in Python    | score: 0.0001
```


## 3. 配置与高级应用

### 3.1 自定义 RerankOptions

可以针对特定模型设置重排模型参数：

```java
RerankOptions options = new RerankOptions();
options.setModel("Qwen3-Reranker-4B");

List<Document> rankedDocs = rerankModel.rerank(query, docs, options);
```

### 3.2 配置 HTTP 请求和结果解析

`DefaultRerankModelConfig` 提供了灵活的配置：

* `endpoint`：API 地址
* `apiKey`：认证信息
* `resultsJsonPath`：JSONPath 表达式，用于从响应中提取结果数组
* `indexJsonKey`：结果中文档索引字段
* `scoreJsonKey`：结果中相关性分数字段

示例：

```java
DefaultRerankModelConfig config = new DefaultRerankModelConfig();
config.setEndpoint("https://api.example.com");
config.setApiKey("your_api_key");
config.setResultsJsonPath("$.results");
config.setIndexJsonKey("index");
config.setScoreJsonKey("relevance_score");
```

### 3.3 异常处理
* **RerankException**：当返回结果为空或解析失败时抛出
* **HttpClientException**：HTTP 请求失败时可能抛出

建议在调用 `rerank()` 时使用 try-catch 捕获：

```java
try {
    List<Document> rankedDocs = rerankModel.rerank(query, docs, options);
} catch (RerankException e) {
    System.err.println("Rerank failed: " + e.getMessage());
}
```

### 3.4 高级应用

1. **批量查询重排**：对多个 query 循环调用 `rerank`
2. **自定义排序规则**：在 `rerank` 返回后，可基于 score 进一步自定义排序
3. **自定义 HTTP 客户端**：通过 `DefaultRerankModel.setHttpClient()` 使用自定义 HttpClient（可设置超时、代理等）
4. **扩展 RerankModel**：继承 `BaseRerankModel` 并实现自定义重排逻辑，例如调用不同的远程模型或本地模型

## 4. 源码结构与核心逻辑

1. **接口定义**：

```java
public interface RerankModel {
    List<Document> rerank(String query, List<Document> documents, RerankOptions options);
}
```

2. **默认实现流程**：

* 构建 HTTP 请求：

    * URL = `config.endpoint + config.requestPath`
    * Header = `Authorization + Content-Type`
    * Payload = `JSON { model, query, documents }`
* POST 请求模型服务
* 解析 JSON 响应，提取文档索引和 score
* 更新原文档列表的 score
* 根据 score 降序排序，返回文档列表





</div>

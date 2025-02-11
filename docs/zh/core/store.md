# Store 存储

Agents-Flex 的 Store 指的是向量存储器 `VectorStore`。 其定义了如下的方法，用于对向量数据进行增删改查：

- `store(List<T> documents, StoreOptions options)` 用于存储向量数据
- `delete(Collection<String> ids, StoreOptions options)` 用于删除向量数据
- `update(List<T> documents, StoreOptions options)` 用于更新向量数据
- `search(SearchWrapper wrapper, StoreOptions options)` 用于查询（召回）向量数据

目前，在 Agents-Flex 中，已经完成了以下向量数据库的适配：
- 阿里云向量检索服务：https://help.aliyun.com/document_detail/2510317.html
- 腾讯云向量数据库：https://cloud.tencent.com/document/product/1709/98666
- Milvus 向量数据库：https://milvus.io

以下更多的向量数据库适配正在完善中:
- **agents-flex-store-chroma** ：chroma 向量数据库
- **agents-flex-store-elasticsearch** ：elasticsearch 向量存储
- **agents-flex-store-opensearch** ：opensearch 向量存储
- **agents-flex-store-redis** ：redis 向量数据存储

## 示例代码

```java
AliyunVectorStoreConfig storeConfig = new AliyunVectorStoreConfig();

//设置阿里云向量数据检索服务的相关配置
storeConfig.setApiKey("...");
storeConfig.setEndpoint("...");
storeConfig.setDatabase("...");

DocumentStore store = new AliyunVectorStore(storeConfig);

//创建 Embedding 模型，
EmbeddingModel llm = new OpenAILlm.of("sk-rts5NF6n*******");

//为 store 配置 Embedding 模型
store.setEmbeddingModel(llm);
```

完成以上的设置，我们就可以愉快的使用 store 来对向量数据进行 `增删改查` 了。

**新增数据：**

```java
Document document = new Document();
document.setId(100);
document.setContent("文档的文本数据...巴拉巴拉");

store.store(document);
```

**更新数据：**

```java
Document document = new Document();
document.setId(100);
document.setContent("新的文档数据...巴拉巴拉");

store.update(document);
```

**删除数据：**

```java
store.delete(100);
```

**数据召回**

```java
SearchWrapper wrapper = new SearchWrapper();
wrapper.setText("关键字或者提示词");

List<Document> result = store.search(wrapper);
```

## SearchWrapper

目前，在向量数据库领域中，并不存在一个类似 SQL 的语言，来统一数据库查询。每一家的向量数据库都是提供了不同的 API 或者独特的查询语言。

Agents-Flex 为了消灭各个向量数据库的查询差异，因而开发了 SearchWrapper，用于对各个向量数据库的统一适配。

SearchWrapper 支持生成 Filter Expression（过滤表达式，类似为 SQL 的 where 部分）用于对向量数据库的进一步过滤。

```java
@Test
public void testSearchWrapper() {
    SearchWrapper rw = new SearchWrapper();
    rw.eq("akey", "avalue").eq(Connector.OR, "bkey", "bvalue").group(rw1 -> {
        rw1.eq("ckey", "avalue").in(Connector.AND_NOT, "dkey", "bvalue");
    }).eq("a", "b");

    String expr = "akey = \"avalue\" " +
        "OR bkey = \"bvalue\" " +
        "AND (ckey = \"avalue\" AND NOT dkey IN \"bvalue\") " +
        "AND a = \"b\"";

    Assert.assertEquals(expr, rw.toFilterExpression());
}
```

过滤表达式可以参考如下：
- 腾讯云向量数据库：https://cloud.tencent.com/document/product/1709/95099
- 阿里云向量检索服务：https://help.aliyun.com/document_detail/2513006.html
- milvus 向量数据库：https://milvus.io/docs/boolean.md

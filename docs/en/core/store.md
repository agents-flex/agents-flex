# Store

Agents-Flex's Store refers to the ` VectorStore `, which defines the following methods for manipulating vector data:

- `store(List<T> documents, StoreOptions options)`: Used to store vector data.
- `delete(Collection<String> ids, StoreOptions options)`: Used to delete vector data.
- `update(List<T> documents, StoreOptions options)`: Used to update vector data.
- `search(SearchWrapper wrapper, StoreOptions options)`: Used to search (retrieve) vector data.

Currently, the following vector databases have been adapted in Agents-Flex:


- Milvus Vector Database: https://milvus.io
- Alibaba Cloud Vector Retrieval Service: https://help.aliyun.com/document_detail/2510317.html
- Tencent Cloud Vector Database: https://cloud.tencent.com/document/product/1709/98666

Additionally, adaptation for more vector databases is currently in progress:

- **agents-flex-store-chroma**: Chroma vector database
- **agents-flex-store-elasticsearch**: Elasticsearch vector storage
- **agents-flex-store-opensearch**: OpenSearch vector storage
- **agents-flex-store-redis**: Redis vector data storage

## Example Code

```java
AliyunVectorStoreConfig storeConfig = new AliyunVectorStoreConfig();

//Configuring Alibaba Cloud Vector Retrieval Service settings
storeConfig.setApiKey("...");
storeConfig.setEndpoint("...");
storeConfig.setDatabase("...");

DocumentStore store = new AliyunVectorStore(storeConfig);

//Create an embedding model，
EmbeddingModel chatModel = new OpenAILlm.of("sk-rts5NF6n*******");

//Configuring the embedding model for the store
store.setEmbeddingModel(chatModel);
```

With the above setup completed, we can happily use the store to perform  `CRUD ` operations on vector data.

**Add new Document:**

```java
Document document = new Document();
document.setId(100);
document.setContent("Text data of the document...");

store.store(document);
```

**Update Document：**

```java
Document document = new Document();
document.setId(100);
document.setContent("New document data...");

store.update(document);
```

**Delete Document:**

```java
store.delete(100);
```

**Data retrieval**

```java
SearchWrapper wrapper = new SearchWrapper();
wrapper.setText("Keywords or prompts");

List<Document> result = store.search(wrapper);
```

## SearchWrapper


Currently, there is no SQL-like language in the field of vector databases to unify database queries. Each vector database provider offers different APIs or unique query languages.

To eliminate the differences in querying among various vector databases, Agents-Flex has developed the `SearchWrapper` for unified adaptation.

The `SearchWrapper` supports generating Filter Expressions (similar to the "where" clause in SQL) for further filtering of vector databases.

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

Filter expressions can be referred to as follows:

- Milvus Vector Database：https://milvus.io/docs/boolean.md
- Tencent Cloud Vector Database：https://cloud.tencent.com/document/product/1709/95099
- Alibaba Cloud Vector Retrieval Service：https://help.aliyun.com/document_detail/2513006.html



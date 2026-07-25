<div v-pre>

# 自定义 VectorStore

## 选择扩展层级

- 数据类型不是 `Document`：继承 `VectorStore<T extends VectorData>`；
- 存储文档，并希望复用切分、ID 和 Embedding：继承 `DocumentStore`；
- 只需要适配新的过滤语言：实现 `ExpressionAdaptor`；
- 目标 SDK 使用结构化过滤对象：像 Chroma/Qdrant 一样编写 Condition Builder，不必先转成字符串。

## 最小 DocumentStore 实现

```java
public final class MyDocumentStore extends DocumentStore {
    private final MyClient client;
    private final String defaultCollection;

    public MyDocumentStore(MyClient client, String defaultCollection) {
        this.client = Objects.requireNonNull(client);
        this.defaultCollection = Objects.requireNonNull(defaultCollection);
    }

    @Override
    protected StoreResult doStore(
        List<Document> documents,
        StoreOptions options
    ) {
        String collection = resolveCollection(options);
        try {
            client.upsert(collection, toRecords(documents));
            return StoreResult.successWithIds(documents);
        } catch (Exception e) {
            return StoreResult.fail("写入失败", e);
        }
    }

    @Override
    protected StoreResult doDelete(
        Collection<?> ids,
        StoreOptions options
    ) {
        try {
            client.delete(resolveCollection(options), ids);
            return StoreResult.success();
        } catch (Exception e) {
            return StoreResult.fail("删除失败", e);
        }
    }

    @Override
    protected StoreResult doUpdate(
        List<Document> documents,
        StoreOptions options
    ) {
        return doStore(documents, options);
    }

    @Override
    protected List<Document> doSearch(
        SearchWrapper wrapper,
        StoreOptions options
    ) {
        MyFilter filter = conditionBuilder.build(wrapper.getCondition());
        return client.search(
            resolveCollection(options),
            wrapper.getVector(),
            wrapper.getMaxResults(),
            wrapper.getMinScore(),
            filter,
            wrapper.isOutputVector()
        ).stream().map(this::toDocument).collect(Collectors.toList());
    }

    private String resolveCollection(StoreOptions options) {
        return options.getCollectionNameOrDefault(defaultCollection);
    }
}
```

父类已经处理空 `StoreOptions`、写入 Embedding 和文本查询 Embedding，`doXxx` 不应重复执行。

## 配置契约

```java
public final class MyStoreConfig implements DocumentStoreConfig {
    private String endpoint;
    private String apiKey;
    private String defaultCollectionName;

    @Override
    public boolean checkAvailable() {
        return StringUtil.allHasText(endpoint, defaultCollectionName);
    }
}
```

`checkAvailable()` 应检查配置完整性。除非接口文档明确说明，它不应在每次调用时执行昂贵的远程健康检查。

## 字符串表达式适配器

```java
final class MyExpressionAdaptor implements ExpressionAdaptor {
    @Override
    public String toOperationSymbol(ConditionType type) {
        if (type == ConditionType.EQ) {
            return " == ";
        }
        return type.getDefaultSymbol();
    }

    @Override
    public String toValue(Condition condition, Object value) {
        return escapeAndQuote(value);
    }
}
```

适配器必须覆盖：

- 字段名验证和引用；
- 字符串转义；
- Number、Boolean 和 NULL 类型；
- 空 IN、NULL IN、BETWEEN 边界；
- AND、OR、NOT 和分组；
- 目标数据库不支持的运算符应明确抛错。

不要用通用 `toString()` 拼接不可信值。目标客户端支持参数绑定或结构化 Filter 时优先使用它们。

## 集合缓存与并发

自动建集合通常需要缓存，但缓存键必须包含集合名以及影响 schema 的配置：

```java
private final Set<String> readyCollections = ConcurrentHashMap.newKeySet();
private final ConcurrentMap<String, Object> collectionLocks =
    new ConcurrentHashMap<>();
```

正确流程是“双重检查 -> 获取集合专属锁 -> 远程检查/创建 -> 校验 schema -> 写入缓存”。不要用一个布尔字段
表示所有集合都已创建，这会造成切换 Collection 后跳过初始化。

## 必测场景

1. 默认集合与两个动态集合相互隔离；
2. 同一新集合并发首次写入；
3. 不同集合并发首次写入；
4. 字符串、数字、Boolean、NULL 和特殊字符 metadata；
5. 所有 ConditionType、连接符和嵌套分组；
6. 空批次、重复 ID、不存在 ID；
7. 向量维度不匹配；
8. 客户端超时、部分批量失败和重试；
9. 关闭资源后的行为；
10. 真实数据库集成测试，而不只有 mock。

</div>

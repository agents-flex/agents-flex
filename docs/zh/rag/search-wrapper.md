# SearchWrapper
<div v-pre>


## 1. 核心概念

### 1.1 SearchWrapper 的定位

`SearchWrapper` 是 Agents-Flex 框架中用于向量存储（Vector Store）搜索的核心封装类。它提供了统一的接口来构建查询条件、控制返回结果、以及与向量检索系统交互。

在 RAG（Retrieval-Augmented Generation）场景中，`SearchWrapper` 主要用于：

1. 在向量数据库中执行搜索。
2. 根据条件过滤查询结果。
3. 自定义输出字段和是否返回向量数据。

### 1.2 核心字段

| 字段             | 类型             | 说明                                   |
| -- | -- |--------------------------------------|
| `text`         | `String`       | 待搜索的文本，会被转换为向量。                      |
| `maxResults`   | `Integer`      | 返回的最大结果数，默认 4。类似 SQL 中的 `LIMIT`。     |
| `minScore`     | `Double`       | 相似度最低阈值，范围 `[0,1]`。0 表示不限制，1 表示完全匹配。 |
| `withVector`   | `boolean`      | 是否自动将文本转换为向量。                        |
| `condition`    | `Condition`    | 查询条件，可支持复杂逻辑组合。                      |
| `outputFields` | `List<String>` | 指定返回字段。                              |
| `outputVector` | `boolean`      | 是否返回向量数据。                            |

### 1.3 条件构建概念

`SearchWrapper` 内部通过 `Condition`、`Group`、`Connector` 等对象构建复杂查询逻辑。

* **ConditionType**：支持 EQ（等于）、NE（不等于）、GT、GE、LT、LE、IN、NIN、BETWEEN 等操作符。
* **Connector**：支持 AND、OR、AND NOT、OR NOT 等逻辑连接符。
* **Group / Not**：支持将条件分组，用于嵌套查询或 NOT 条件。

这种设计允许开发者以链式 API 构建复杂的搜索条件。



## 2. 快速入门

### 2.1 简单文本搜索

```java
SearchWrapper search = new SearchWrapper()
    .text("人工智能")
    .maxResults(5);
```

说明：搜索文本 `"人工智能"`，返回最多 5 条结果。

### 2.2 条件搜索

```java
SearchWrapper search = new SearchWrapper()
    .text("大模型")
    .eq("category", "AI")          // category = "AI"
    .gt("score", 0.8);             // score > 0.8
```

说明：同时指定类别和分数条件。

### 2.3 多条件组合

```java
SearchWrapper search = new SearchWrapper()
    .text("深度学习")
    .andCriteria(sw -> sw
        .eq("category", "AI")
        .gt("score", 0.7)
    )
    .orCriteria(sw -> sw
        .eq("category", "ML")
        .lt("score", 0.5)
    );
```

说明：`AND` 和 `OR` 嵌套组合，支持复杂逻辑查询。

### 2.4 指定输出字段

```java
search.outputFields("id", "title", "summary");
```

说明：只返回指定字段，减少数据传输量。

### 2.5 返回向量数据

```java
search.outputVector(true);
```

说明：将搜索结果的向量信息一并返回，可用于二次向量计算。



## 3. 高级配置与应用

### 3.1 最小相似度过滤

```java
search.minScore(0.75);
```

说明：只返回相似度 >= 0.75 的结果，适合语义搜索精度控制。

### 3.2 使用 `in` 和 `between` 查询

```java
search.in("tags", Arrays.asList("AI", "ML", "NLP"));
search.between("createdAt", "2025-01-01", "2025-12-31");
```

说明：支持集合匹配及区间过滤。

### 3.3 自定义条件表达式

`SearchWrapper` 可通过 `toFilterExpression(ExpressionAdaptor adaptor)` 输出不同数据库或向量存储的适配表达式。

```java
String expr = search.toFilterExpression(ChromaExpressionAdaptor.DEFAULT);
```

* `ExpressionAdaptor` 支持自定义不同存储的语法，例如 Chroma、Milvus 等。
* 默认提供 `ExpressionAdaptor.DEFAULT`。

### 3.4 嵌套逻辑和 NOT 条件

```java
search.orCriteria(sw -> sw
    .not(new Condition(ConditionType.EQ, new Key("status"), new Value("inactive")))
    .andCriteria(inner -> inner
        .gt("score", 0.7)
        .eq("category", "AI")
    )
);
```

说明：可以通过 `Not`、`Group` 和链式 API 构建任意复杂的查询逻辑。

### 3.5 动态构建查询

使用 Java `Consumer` 动态构建条件：

```java
search.group(sw -> {
    sw.eq("category", "AI");
    sw.gt("score", 0.8);
});
```

优势：灵活封装逻辑，可按条件动态生成搜索表达式。



## 4. 实战案例

### 案例 1：语义搜索与过滤结合

```java
SearchWrapper search = new SearchWrapper()
    .text("大语言模型")
    .minScore(0.8)
    .outputFields("id", "title")
    .andCriteria(sw -> sw
        .eq("category", "AI")
        .between("createdAt", "2025-01-01", "2025-12-31")
    );
```

说明：返回相似度 >= 0.8，且在指定时间区间内的 AI 类别文档。

### 案例 2：多条件 OR 查询

```java
SearchWrapper search = new SearchWrapper()
    .text("生成式 AI")
    .orCriteria(sw -> sw
        .eq("category", "AI")
        .lt("score", 0.5)
    )
    .orCriteria(sw -> sw
        .eq("category", "ML")
        .gt("score", 0.7)
    );
```

说明：返回两类文档条件的并集。


</div>

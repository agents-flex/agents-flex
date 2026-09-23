# 结果游标

GraphResultCursor 是可关闭的 Iterator，用于逐条读取 GraphRecord。

~~~java
try (GraphResultCursor cursor =
         graph.query().executeCursor(query, options)) {
    while (cursor.hasNext()) {
        GraphRecord record = cursor.next();
        consume(record);
    }
}
~~~

## 生命周期

游标使用完必须 close。close 应该是幂等的；关闭后 hasNext 返回 false，继续 next 会失败。

## 后端差异

Neo4j 适配器使用 Driver 的流式结果，游标关闭会释放 Session。Nebula SessionPool 当前返回物化 ResultSet，
executeCursor 使用统一的内存包装器，并不代表后端真正支持流式读取。

## maxRecords 和截断

游标同样遵守 GraphOptions.maxRecords。达到上限后，metadata.truncated 会说明后端仍有未返回记录。需要继续
读取时改用分页，而不是重复读取同一游标。


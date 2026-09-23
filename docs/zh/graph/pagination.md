# 分页

GraphQueryExecutor.executePage 提供统一分页协议。

~~~java
GraphPageResult first = graph.query().executePage(
    query, GraphPageRequest.of(0, 20), options);

if (first.hasNext()) {
    GraphPageResult next = graph.query().executePage(
        query, GraphPageRequest.after(first.getNextCursor(), 20), options);
}
~~~

## 分页规则

- limit 必须为正数且不能超过 SDK 上限；
- 第一页使用 offset；
- 后续页使用 opaque cursor；
- 调用方不应解析或拼接 cursor；
- 应使用稳定排序保证页之间顺序一致；
- 分页结果只包含当前页，look-ahead 记录不会泄漏。

默认实现使用 offset cursor。适配器可以覆写为后端原生 keyset cursor；不支持的 cursor 会明确失败。

## 数据变化

offset 分页在并发插入、删除时可能出现重复或跳过。强一致导出应使用数据库快照、稳定版本条件或专属 keyset
分页，而不是仅依赖 offset。


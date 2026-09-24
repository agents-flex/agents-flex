package com.agentsflex.graph.query;

import com.agentsflex.graph.GraphOptions;

/**
 * 执行可移植查询和显式原生查询。
 */
public interface GraphQueryExecutor {
    /**
     * 执行统一查询语义。
     */
    GraphResult execute(GraphQuery query, GraphOptions options);

    /**
     * 执行后端原生查询。
     */
    GraphResult execute(NativeGraphQuery query, GraphOptions options);

    /**
     * 使用默认选项执行统一查询。
     */
    default GraphResult execute(GraphQuery query) {
        return execute(query, GraphOptions.DEFAULT);
    }

    /**
     * 使用默认选项执行原生查询。
     */
    default GraphResult execute(NativeGraphQuery query) {
        return execute(query, GraphOptions.DEFAULT);
    }

    /**
     * 执行可移植查询的分页请求。当前统一 offset token 只适用于 {@link TraversalQuery}；适配器
     * 可以覆写为后端原生 keyset 分页。
     */
    default GraphPageResult executePage(GraphQuery query, GraphPageRequest page, GraphOptions options) {
        if (!(query instanceof TraversalQuery)) {
            throw new com.agentsflex.graph.UnsupportedGraphFeatureException(
                "Portable paging is only defined for TraversalQuery");
        }
        GraphPageRequest resolved = page == null ? GraphPageRequest.of(0, 100) : page;
        if (!resolved.getCursor().isEmpty() && !resolved.getCursor().startsWith("offset:")) {
            throw new com.agentsflex.graph.UnsupportedGraphFeatureException(
                "Portable default paging only understands offset cursors");
        }
        String queryFingerprint = GraphQueryFingerprint.of((TraversalQuery) query);
        if (!resolved.getQueryFingerprint().isEmpty()
            && !resolved.getQueryFingerprint().equals(queryFingerprint)) {
            throw new IllegalArgumentException("graph page cursor belongs to a different query");
        }
        // 多取一条记录探测下一页；适配器可覆写为原生 keyset 分页。
        int lookAhead = resolved.getLimit() == 10_000 ? resolved.getLimit() : resolved.getLimit() + 1;
        GraphOptions resolvedOptions = options == null ? GraphOptions.DEFAULT : options;
        if (resolvedOptions.getMaxRecords() < lookAhead) resolvedOptions = resolvedOptions.withMaxRecords(lookAhead);
        GraphResult result = execute(((TraversalQuery) query).page(GraphPageRequest.of(resolved.getOffset(), lookAhead)), resolvedOptions);
        boolean nextPage = result.getRecords().size() > resolved.getLimit() || result.getMetadata().isTruncated();
        String next = nextPage
            ? "offset:" + (resolved.getOffset() + Math.min(resolved.getLimit(), result.getRecords().size()))
            + ":" + queryFingerprint : "";
        GraphResult pageResult = result.forPage(resolved.getLimit(), nextPage, next);
        return new GraphPageResult(pageResult, next);
    }

    /**
     * 使用默认选项执行分页查询。
     */
    default GraphPageResult executePage(GraphQuery query, GraphPageRequest page) {
        return executePage(query, page, GraphOptions.DEFAULT);
    }

    /**
     * 以游标方式执行统一查询；默认实现把已有物化结果包装成游标，适配器可覆写为流式实现。
     */
    default GraphResultCursor executeCursor(GraphQuery query, GraphOptions options) {
        return GraphResultCursors.of(execute(query, options));
    }

    /**
     * 以游标方式执行原生查询。
     */
    default GraphResultCursor executeCursor(NativeGraphQuery query, GraphOptions options) {
        return GraphResultCursors.of(execute(query, options));
    }

    /**
     * 请求后端执行计划；适配器不支持时显式抛出 UnsupportedGraphFeatureException。
     */
    default GraphExplainResult explain(GraphQuery query, GraphOptions options) {
        throw new com.agentsflex.graph.UnsupportedGraphFeatureException("Graph backend does not expose query plans");
    }

    /**
     * 请求原生查询执行计划。
     */
    default GraphExplainResult explain(NativeGraphQuery query, GraphOptions options) {
        throw new com.agentsflex.graph.UnsupportedGraphFeatureException("Graph backend does not expose query plans");
    }
}

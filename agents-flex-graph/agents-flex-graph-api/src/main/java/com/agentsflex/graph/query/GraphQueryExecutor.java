package com.agentsflex.graph.query;

import com.agentsflex.graph.GraphOptions;

import java.util.Map;

/**
 * 执行可移植查询和显式原生查询。
 */
public interface GraphQueryExecutor {
    /**
     * 解析并执行公共查询字符串。
     *
     * <p>该便捷入口不会把字符串直接拼接到后端语句，而是先转换为
     * {@link TraversalQuery}，因此仍然享有统一校验和参数化编译能力。</p>
     *
     * @param expression Graph 公共查询表达式
     * @param parameters 命名参数
     * @param options    执行选项
     * @return 查询结果
     */
    default GraphResult execute(String expression, Map<String, ?> parameters, GraphOptions options) {
        return execute(GraphQueryParser.parse(expression, parameters).getGraphQuery(), options);
    }

    /**
     * 使用默认执行选项执行公共查询字符串。
     */
    default GraphResult execute(String expression, Map<String, ?> parameters) {
        return execute(expression, parameters, GraphOptions.DEFAULT);
    }

    /**
     * 使用指定执行选项执行不含命名参数的公共查询字符串。
     */
    default GraphResult execute(String expression, GraphOptions options) {
        return execute(expression, java.util.Collections.<String, Object>emptyMap(), options);
    }

    /**
     * 执行只包含字面量的公共查询字符串；调用方无需显式创建空参数 Map。
     */
    default GraphResult execute(String expression) {
        return execute(expression, java.util.Collections.<String, Object>emptyMap(), GraphOptions.DEFAULT);
    }

    /**
     * 绑定并执行预解析的公共查询模板。
     */
    default GraphResult execute(GraphQueryTemplate template, Map<String, ?> parameters,
                                GraphOptions options) {
        if (template == null) throw new IllegalArgumentException("query template must not be null");
        return execute(template.bind(parameters), options);
    }

    /**
     * 使用默认执行选项绑定并执行预解析查询模板。
     */
    default GraphResult execute(GraphQueryTemplate template, Map<String, ?> parameters) {
        return execute(template, parameters, GraphOptions.DEFAULT);
    }

    /**
     * 解析并执行公共查询字符串的分页请求。
     *
     * <p>分页游标仍由 {@link #executePage(GraphQuery, GraphPageRequest, GraphOptions)} 统一生成和校验，
     * 字符串入口不会自行解析或修改 opaque cursor。</p>
     *
     * @param expression 公共查询字符串
     * @param parameters 命名参数，可以为 {@code null}
     * @param page       页请求；为空时使用默认分页
     * @param options    执行选项，可以为 {@code null}
     * @return 当前页结果和下一页 opaque cursor
     */
    default GraphPageResult executePage(String expression, Map<String, ?> parameters,
                                        GraphPageRequest page, GraphOptions options) {
        return executePage(GraphQueryParser.parse(expression, parameters).getGraphQuery(), page, options);
    }

    /**
     * 使用默认执行选项执行公共查询字符串分页。
     */
    default GraphPageResult executePage(String expression, Map<String, ?> parameters,
                                        GraphPageRequest page) {
        return executePage(expression, parameters, page, GraphOptions.DEFAULT);
    }

    /**
     * 执行不含命名参数的公共查询字符串分页。
     */
    default GraphPageResult executePage(String expression, GraphPageRequest page, GraphOptions options) {
        return executePage(expression, java.util.Collections.<String, Object>emptyMap(), page, options);
    }

    /**
     * 使用默认执行选项执行不含命名参数的公共查询字符串分页。
     */
    default GraphPageResult executePage(String expression, GraphPageRequest page) {
        return executePage(expression, page, GraphOptions.DEFAULT);
    }

    /**
     * 解析并打开公共查询字符串的结果游标。
     *
     * <p>后端支持流式查询时会返回真正的后端游标，否则使用统一的物化结果游标。</p>
     *
     * @param expression 公共查询字符串
     * @param parameters 命名参数，可以为 {@code null}
     * @param options    执行选项，可以为 {@code null}
     * @return 需要由调用方关闭的结果游标
     */
    default GraphResultCursor executeCursor(String expression, Map<String, ?> parameters,
                                            GraphOptions options) {
        return executeCursor(GraphQueryParser.parse(expression, parameters).getGraphQuery(), options);
    }

    /**
     * 使用默认执行选项打开公共查询字符串游标。
     */
    default GraphResultCursor executeCursor(String expression, Map<String, ?> parameters) {
        return executeCursor(expression, parameters, GraphOptions.DEFAULT);
    }

    /**
     * 打开不含命名参数的公共查询字符串游标。
     */
    default GraphResultCursor executeCursor(String expression, GraphOptions options) {
        return executeCursor(expression, java.util.Collections.<String, Object>emptyMap(), options);
    }

    /**
     * 使用默认执行选项打开不含命名参数的公共查询字符串游标。
     */
    default GraphResultCursor executeCursor(String expression) {
        return executeCursor(expression, GraphOptions.DEFAULT);
    }

    /**
     * 解析并请求公共查询字符串的执行计划。
     *
     * @param expression 公共查询字符串
     * @param parameters 命名参数，可以为 {@code null}
     * @param options    执行选项，可以为 {@code null}
     * @return 后端执行计划
     */
    default GraphExplainResult explain(String expression, Map<String, ?> parameters,
                                       GraphOptions options) {
        return explain(GraphQueryParser.parse(expression, parameters).getGraphQuery(), options);
    }

    /**
     * 使用默认执行选项请求公共查询字符串执行计划。
     */
    default GraphExplainResult explain(String expression, Map<String, ?> parameters) {
        return explain(expression, parameters, GraphOptions.DEFAULT);
    }

    /**
     * 请求不含命名参数的公共查询字符串执行计划。
     */
    default GraphExplainResult explain(String expression, GraphOptions options) {
        return explain(expression, java.util.Collections.<String, Object>emptyMap(), options);
    }

    /**
     * 使用默认执行选项请求不含命名参数的公共查询字符串执行计划。
     */
    default GraphExplainResult explain(String expression) {
        return explain(expression, GraphOptions.DEFAULT);
    }

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

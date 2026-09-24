package com.agentsflex.graph.query;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 字符串公共查询解析后的不可变结果。
 *
 * <p>查询已经转换为 SDK 的统一 AST；参数映射作为审计和调用方追踪信息保留，但不会在
 * 编译器中再次拼接回查询文本。</p>
 */
public final class ParsedGraphQuery {
    /**
     * 原始查询文本。
     */
    private final String expression;
    /**
     * 统一查询 AST，可以是线性遍历或 UNION 组合。
     */
    private final GraphQuery query;
    /**
     * 解析时使用的命名参数快照。
     */
    private final Map<String, Object> parameters;

    /**
     * 创建解析结果并冻结参数。
     *
     * <p>解析器传入的参数通常已经是深度只读快照；这里再次复制外层 Map，保证即使该类
     * 被单独实例化，也不会暴露可变容器。</p>
     *
     * @param expression 原始查询文本
     * @param query      已构建并校验的统一查询 AST
     * @param parameters 解析时使用的参数快照
     */
    public ParsedGraphQuery(String expression, TraversalQuery query, Map<String, ?> parameters) {
        this(expression, (GraphQuery) query, parameters);
    }

    /**
     * 创建包含任意公共查询类型的解析结果。
     */
    public ParsedGraphQuery(String expression, GraphQuery query, Map<String, ?> parameters) {
        if (query == null) throw new IllegalArgumentException("parsed query must not be null");
        this.expression = expression == null ? "" : expression;
        this.query = query;
        Map<String, Object> copy = new LinkedHashMap<>();
        if (parameters != null) copy.putAll(parameters);
        this.parameters = Collections.unmodifiableMap(copy);
    }

    /**
     * @return 原始查询文本
     */
    public String getExpression() {
        return expression;
    }

    /**
     * @return 已校验的统一查询 AST
     */
    public TraversalQuery getQuery() {
        if (!(query instanceof TraversalQuery)) {
            throw new IllegalStateException("parsed query is not a single TraversalQuery; use getGraphQuery()");
        }
        return (TraversalQuery) query;
    }

    /**
     * @return 完整公共查询 AST，包括 TraversalQuery 或 GraphUnionQuery。
     */
    public GraphQuery getGraphQuery() {
        return query;
    }

    /**
     * @return 解析时使用的只读参数
     */
    public Map<String, Object> getParameters() {
        return parameters;
    }
}

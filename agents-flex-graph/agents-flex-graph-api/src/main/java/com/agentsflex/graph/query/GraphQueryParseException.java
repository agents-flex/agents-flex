package com.agentsflex.graph.query;

/**
 * Graph 公共查询语言的词法、语法或参数错误。
 *
 * <p>异常保留原始表达式中的字符位置，便于上层编辑器直接定位错误。位置从 0 开始，
 * {@link #getColumn()} 则从 1 开始，适合展示给最终用户。</p>
 */
public final class GraphQueryParseException extends IllegalArgumentException {
    /**
     * 原始查询文本，便于编辑器或日志重新定位错误。
     */
    private final String expression;
    /**
     * 错误字符的 0-based 偏移；空查询错误固定为 0。
     */
    private final int position;
    /**
     * 稳定错误分类。
     */
    private final GraphQueryErrorKind kind;

    /**
     * 创建带位置的查询解析异常。
     *
     * @param expression 原始查询
     * @param position   错误位置
     * @param message    错误说明
     */
    public GraphQueryParseException(String expression, int position, String message) {
        this(expression, position, message, inferKind(message));
    }

    /**
     * 创建带位置和稳定分类的查询解析异常。
     */
    public GraphQueryParseException(String expression, int position, String message, GraphQueryErrorKind kind) {
        super(message + " at column " + (Math.max(0, position) + 1));
        this.expression = expression == null ? "" : expression;
        this.position = Math.max(0, position);
        this.kind = kind == null ? GraphQueryErrorKind.SYNTAX : kind;
    }

    /**
     * @return 错误分类。
     */
    public GraphQueryErrorKind getKind() {
        return kind;
    }

    /**
     * 兼容旧调用点的轻量分类推断。
     */
    private static GraphQueryErrorKind inferKind(String message) {
        if (message != null && (message.contains("missing parameter") || message.contains("parameter must"))) {
            return GraphQueryErrorKind.PARAMETER;
        }
        if (message != null && message.contains("values must")) return GraphQueryErrorKind.SEMANTIC;
        if (message != null && (message.contains("alias") || message.contains("property")
            || message.contains("label") || message.contains("type") || message.contains("projection"))) {
            return GraphQueryErrorKind.SEMANTIC;
        }
        return GraphQueryErrorKind.SYNTAX;
    }

    /**
     * 返回触发错误的完整原始表达式。
     *
     * @return 原始查询文本；传入 {@code null} 时返回空字符串
     */
    public String getExpression() {
        return expression;
    }

    /**
     * 返回错误 token 的 0-based 字符偏移。
     *
     * @return 错误位置
     */
    public int getPosition() {
        return position;
    }

    /**
     * @return 面向用户展示的 1-based 列号
     */
    public int getColumn() {
        return position + 1;
    }
}

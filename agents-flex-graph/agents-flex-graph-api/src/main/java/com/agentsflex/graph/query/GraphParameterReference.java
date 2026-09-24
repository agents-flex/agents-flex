package com.agentsflex.graph.query;

/**
 * 公共查询模板中的参数占位符。
 *
 * <p>该类型只在 SDK 内部的参数化 AST 中出现，不会直接传递给后端驱动。调用
 * {@link GraphQueryTemplate#bind(java.util.Map)} 时，所有占位符都会被替换成调用方提供的实际值。</p>
 */
final class GraphParameterReference {
    /**
     * 参数名称。
     */
    private final String name;

    /**
     * 创建参数引用。
     */
    GraphParameterReference(String name) {
        this.name = name;
    }

    /**
     * @return 参数名称。
     */
    String getName() {
        return name;
    }

    @Override
    public String toString() {
        return ":" + name;
    }
}

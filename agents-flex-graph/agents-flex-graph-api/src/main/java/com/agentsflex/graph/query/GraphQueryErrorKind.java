package com.agentsflex.graph.query;

/**
 * 公共查询错误的稳定分类，便于编辑器和服务层区分提示方式。
 */
public enum GraphQueryErrorKind {
    /**
     * 字符无法被词法分析器识别。
     */
    LEXICAL,
    /**
     * 查询结构不符合公共 DSL 语法。
     */
    SYNTAX,
    /**
     * 命名参数缺失或参数类型不符合运算符要求。
     */
    PARAMETER,
    /**
     * 语法正确但违反统一 AST 的语义约束。
     */
    SEMANTIC
}

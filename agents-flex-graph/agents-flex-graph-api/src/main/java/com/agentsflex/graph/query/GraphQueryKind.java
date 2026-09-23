package com.agentsflex.graph.query;

/**
 * 原生查询的意图分类。
 *
 * <p>该分类由调用方显式声明，适配器可以据此执行只读保护、审计记录或能力检查；
 * SDK 不会尝试解析任意后端方言文本来推断真实意图。</p>
 */
public enum GraphQueryKind {
    /**
     * 只读查询。
     */
    READ,
    /**
     * 会修改节点或边数据的查询。
     */
    WRITE,
    /**
     * 会修改 Schema 的查询。
     */
    SCHEMA,
    /**
     * 数据库管理查询。
     */
    ADMIN
}

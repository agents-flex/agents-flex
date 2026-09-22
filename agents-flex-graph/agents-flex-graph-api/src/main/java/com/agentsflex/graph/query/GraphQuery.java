package com.agentsflex.graph.query;


/**
 * 可在后端编译前校验的可移植图查询。
 */
public interface GraphQuery {
    /**
     * 校验别名、范围和后端无关的查询约束。
     */
    void validate();
}

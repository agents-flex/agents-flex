package com.agentsflex.graph.query;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 后端执行计划的统一承载。
 *
 * <p>SDK 只统一后端名称、可读计划文本和可序列化详情，不强行把 Neo4j、Nebula 等数据库的
 * 计划树抹平成同一种结构；调用方应根据 {@link #getBackend()} 决定如何展示详情。</p>
 */
public final class GraphExplainResult {
    /**
     * 产生执行计划的后端标识。
     */
    private final String backend;
    /**
     * 面向日志或查询工具的人类可读计划文本。
     */
    private final String planText;
    /**
     * 后端特有的结构化计划详情。
     */
    private final Map<String, Object> details;

    /**
     * 创建执行计划结果并冻结详情映射。
     *
     * @param backend  后端标识
     * @param planText 可读计划文本
     * @param details  后端特有详情，为 {@code null} 时按空映射处理
     */
    public GraphExplainResult(String backend, String planText, Map<String, ?> details) {
        this.backend = backend == null ? "" : backend;
        this.planText = planText == null ? "" : planText;
        Map<String, Object> copy = new LinkedHashMap<>();
        if (details != null) copy.putAll(details);
        this.details = Collections.unmodifiableMap(copy);
    }

    /**
     * @return 后端标识。
     */
    public String getBackend() {
        return backend;
    }

    /**
     * @return 可读计划文本。
     */
    public String getPlanText() {
        return planText;
    }

    /**
     * @return 只读的后端特有计划详情。
     */
    public Map<String, Object> getDetails() {
        return details;
    }
}

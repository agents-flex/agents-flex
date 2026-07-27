/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.toolsearch;

/**
 * Tool 搜索请求。
 *
 * <p>查询文本用于描述模型当前需要的能力；最大结果数限制暴露给模型的 Tool 数量；
 * 分类可由 Provider 用于缩小检索范围。</p>
 */
public class ToolSearchRequest {
    /**
     * 未显式指定时默认最多返回的结果数。
     */
    public static final int DEFAULT_MAX_RESULTS = 5;
    /**
     * 描述所需能力的查询文本。
     */
    private String query;
    /**
     * 最大返回结果数。
     */
    private int maxResults = DEFAULT_MAX_RESULTS;
    /**
     * 可选的 Tool 分类过滤条件。
     */
    private String category;

    /**
     * 创建空请求，供 Bean 映射或 Setter 方式配置。
     */
    public ToolSearchRequest() {
    }

    /**
     * 使用查询文本创建请求，最大结果数保留默认值 {@value #DEFAULT_MAX_RESULTS}。
     *
     * @param query 描述所需能力的查询文本
     */
    public ToolSearchRequest(String query) {
        this.query = query;
    }

    /**
     * @return 查询文本
     */
    public String getQuery() {
        return query;
    }

    /**
     * @param query 描述所需能力的查询文本
     */
    public void setQuery(String query) {
        this.query = query;
    }

    /**
     * @return 最大返回结果数
     */
    public int getMaxResults() {
        return maxResults;
    }

    /**
     * @param maxResults 最大返回结果数，必须大于 0
     * @throws IllegalArgumentException 当 maxResults 小于或等于 0 时抛出
     */
    public void setMaxResults(int maxResults) {
        if (maxResults <= 0) throw new IllegalArgumentException("maxResults must be greater than zero");
        this.maxResults = maxResults;
    }

    /**
     * @return 可选分类；未设置时返回 {@code null}
     */
    public String getCategory() {
        return category;
    }

    /**
     * @param category Tool 分类过滤条件
     */
    public void setCategory(String category) {
        this.category = category;
    }
}

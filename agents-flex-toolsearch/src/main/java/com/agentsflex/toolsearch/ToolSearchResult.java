/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.toolsearch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Provider 返回的单条 Tool 搜索结果。
 *
 * <p>除了 Tool 元数据，还可以携带相关度分数和命中字段，便于排序、调试或自定义展示。
 * {@link ToolSearchTool} 最终只会激活能够由 Manager 解析到本地执行对象的结果。</p>
 */
public class ToolSearchResult {
    /**
     * 命中的 Tool 元数据。
     */
    private ToolInfo toolInfo;
    /**
     * Provider 计算的相关度分数，数值越大通常表示越相关。
     */
    private double score;
    /**
     * 参与命中的字段名称，例如 name、description、tags 或 parameters。
     */
    private List<String> matchedFields = Collections.emptyList();

    /**
     * 创建空结果，供 Bean 映射或 Setter 方式配置。
     */
    public ToolSearchResult() {
    }

    /**
     * 创建完整搜索结果。
     *
     * @param toolInfo      命中的 Tool 元数据
     * @param score         相关度分数
     * @param matchedFields 命中的字段名称
     */
    public ToolSearchResult(ToolInfo toolInfo, double score, List<String> matchedFields) {
        this.toolInfo = toolInfo;
        this.score = score;
        setMatchedFields(matchedFields);
    }

    /**
     * @return 命中的 Tool 元数据
     */
    public ToolInfo getToolInfo() {
        return toolInfo;
    }

    /**
     * @param toolInfo 命中的 Tool 元数据
     */
    public void setToolInfo(ToolInfo toolInfo) {
        this.toolInfo = toolInfo;
    }

    /**
     * @return 相关度分数
     */
    public double getScore() {
        return score;
    }

    /**
     * @param score 相关度分数
     */
    public void setScore(double score) {
        this.score = score;
    }

    /**
     * @return 不可修改的命中字段列表
     */
    public List<String> getMatchedFields() {
        return Collections.unmodifiableList(matchedFields);
    }

    /**
     * 设置命中字段。传入内容会被复制。
     *
     * @param matchedFields 命中的字段；传入 {@code null} 表示空列表
     */
    public void setMatchedFields(List<String> matchedFields) {
        this.matchedFields = matchedFields == null ? Collections.emptyList() : new ArrayList<>(matchedFields);
    }
}

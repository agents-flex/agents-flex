/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.toolsearch.memory;

import com.agentsflex.toolsearch.ToolInfo;
import com.agentsflex.toolsearch.ToolParameterInfo;
import com.agentsflex.toolsearch.ToolSearchProvider;
import com.agentsflex.toolsearch.ToolSearchRequest;
import com.agentsflex.toolsearch.ToolSearchResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 默认的内存 Tool 元数据存储与加权词法搜索实现。
 *
 * <p>该实现使用 {@link ConcurrentHashMap} 保存元数据，每次查询对全部 Tool 执行一次
 * O(n) 扫描，不依赖 Lucene、Elasticsearch、向量数据库或 Embedding 模型。对于通常只有
 * 几十到几百个 Tool 的应用，这种实现部署简单，性能也足够；工具规模很大或需要语义
 * 召回时，可以替换为自定义 {@link ToolSearchProvider}。</p>
 *
 * <p>搜索会对名称、描述、分类、标签以及参数名称和描述分别加权，按得分降序返回；
 * 分数相同时按 Tool 名称排序，以保证结果稳定。该算法是词法匹配，不理解同义词语义。</p>
 */
public class InMemoryToolSearchProvider implements ToolSearchProvider {
    /** 线程安全的元数据目录，以 Tool 名称为唯一键。 */
    private final ConcurrentMap<String, ToolInfo> tools = new ConcurrentHashMap<>();

    /**
     * 保存或覆盖 Tool 元数据。
     *
     * @param toolInfo 要保存的元数据，名称不能为空
     * @throws IllegalArgumentException 当元数据或名称为空时抛出
     */
    @Override
    public void save(ToolInfo toolInfo) {
        if (toolInfo == null || !hasText(toolInfo.getName())) {
            throw new IllegalArgumentException("ToolInfo and its name must not be blank");
        }
        tools.put(toolInfo.getName(), toolInfo);
    }

    /**
     * @param name Tool 名称
     * @return 同名元数据；不存在或 name 为 {@code null} 时返回 {@code null}
     */
    @Override
    public ToolInfo findByName(String name) { return name == null ? null : tools.get(name); }

    /**
     * 返回全部元数据的快照，并按 Tool 名称升序排列。
     *
     * @return 新建的元数据列表
     */
    @Override
    public List<ToolInfo> findAll() {
        List<ToolInfo> result = new ArrayList<>(tools.values());
        result.sort(Comparator.comparing(ToolInfo::getName));
        return result;
    }

    /**
     * 执行加权词法搜索。
     *
     * <p>设置 category 时先做忽略大小写和符号差异的精确分类过滤。query 为空时不评分，
     * 直接按名称返回分类范围内的前 N 个 Tool；query 非空时只保留得分大于 0 的结果。</p>
     *
     * @param request 搜索请求
     * @return 最多 {@link ToolSearchRequest#getMaxResults()} 条排序结果
     * @throws IllegalArgumentException 当 request 为 {@code null} 时抛出
     */
    @Override
    public List<ToolSearchResult> search(ToolSearchRequest request) {
        if (request == null) throw new IllegalArgumentException("ToolSearchRequest must not be null");
        String query = normalize(request.getQuery());
        List<String> tokens = tokenize(request.getQuery());
        List<ToolSearchResult> results = new ArrayList<>();
        // 分类过滤使用规范化后的精确匹配，避免分类子串造成跨业务域误召回。
        for (ToolInfo tool : tools.values()) {
            if (hasText(request.getCategory())
                && !normalize(request.getCategory()).equals(normalize(tool.getCategory()))) continue;
            Score score = score(tool, query, tokens);
            if (!hasText(query) || score.value > 0) {
                results.add(new ToolSearchResult(tool, score.value, new ArrayList<>(score.fields)));
            }
        }
        // ConcurrentHashMap 没有遍历顺序，使用名称作为同分排序键保证结果可重复。
        results.sort(Comparator.comparingDouble(ToolSearchResult::getScore).reversed()
            .thenComparing(result -> result.getToolInfo().getName()));
        return results.size() > request.getMaxResults()
            ? new ArrayList<>(results.subList(0, request.getMaxResults())) : results;
    }

    /**
     * @param name 要删除的 Tool 名称
     * @return 是否删除了已存在的元数据
     */
    @Override
    public boolean remove(String name) { return name != null && tools.remove(name) != null; }

    /** 清空内存中的全部 Tool 元数据。 */
    @Override
    public void clear() { tools.clear(); }

    private static Score score(ToolInfo tool, String query, List<String> tokens) {
        Score score = new Score();
        String name = normalize(tool.getName());
        String description = normalize(tool.getDescription());
        String category = normalize(tool.getCategory());
        // 完整名称和名称短语具有最高权重，优先返回模型已经明确指出的 Tool。
        if (hasText(query) && name.equals(query)) score.add(120, "name");
        else if (hasText(query) && name.contains(query)) score.add(50, "name");
        if (hasText(query) && description.contains(query)) score.add(30, "description");

        int matchedTokens = 0;
        for (String token : tokens) {
            boolean matched = false;
            // 单词级名称匹配高于子串匹配；分类、标签、描述和参数提供补充召回信号。
            if (containsWord(name, token)) { score.add(25, "name"); matched = true; }
            else if (name.contains(token)) { score.add(12, "name"); matched = true; }
            if (description.contains(token)) { score.add(6, "description"); matched = true; }
            if (category.equals(token)) { score.add(20, "category"); matched = true; }
            else if (category.contains(token)) { score.add(8, "category"); matched = true; }
            for (String tag : tool.getTags()) {
                if (normalize(tag).contains(token)) { score.add(15, "tags"); matched = true; }
            }
            if (parametersContain(tool.getParameters(), token, score)) matched = true;
            if (matched) matchedTokens++;
        }
        // 查询词覆盖率奖励避免只偶然命中一个常见词的 Tool 排在多词命中结果之前。
        if (!tokens.isEmpty()) score.value += 20.0 * matchedTokens / tokens.size();
        return score;
    }

    /** 递归检查嵌套参数，使对象参数中的字段名和说明也可以参与检索。 */
    private static boolean parametersContain(List<ToolParameterInfo> parameters, String token, Score score) {
        boolean matched = false;
        for (ToolParameterInfo parameter : parameters) {
            if (normalize(parameter.getName()).contains(token)) { score.add(10, "parameters"); matched = true; }
            if (normalize(parameter.getDescription()).contains(token)) { score.add(4, "parameters"); matched = true; }
            if (parametersContain(parameter.getChildren(), token, score)) matched = true;
        }
        return matched;
    }

    private static boolean containsWord(String value, String token) {
        return Arrays.asList(value.split(" ")).contains(token);
    }

    private static List<String> tokenize(String value) {
        String normalized = normalize(value);
        if (!hasText(normalized)) return Collections.emptyList();
        return new ArrayList<>(new LinkedHashSet<>(Arrays.asList(normalized.split(" "))));
    }

    /**
     * 将驼峰名称、标点和大小写统一为空格分隔的小写文本，兼容中英文及数字。
     */
    private static String normalize(String value) {
        if (value == null) return "";
        return value.replaceAll("([\\p{Ll}\\d])(\\p{Lu})", "$1 $2")
            .replaceAll("[^\\p{L}\\p{N}]+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }

    private static class Score {
        /** 当前累计相关度。 */
        private double value;
        /** 去重后的命中字段，便于调用方观察得分来源。 */
        private final Set<String> fields = new LinkedHashSet<>();
        private void add(double points, String field) { value += points; fields.add(field); }
    }
}

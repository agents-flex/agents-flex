/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.toolsearch.semantic;

import com.agentsflex.core.model.embedding.EmbeddingModel;
import com.agentsflex.core.store.VectorData;
import com.agentsflex.toolsearch.ToolInfo;
import com.agentsflex.toolsearch.ToolParameterInfo;
import com.agentsflex.toolsearch.ToolSearchProvider;
import com.agentsflex.toolsearch.ToolSearchRequest;
import com.agentsflex.toolsearch.ToolSearchResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 基于 {@link EmbeddingModel} 的进程内语义 Tool 搜索 Provider。
 *
 * <p>Tool 元数据在 {@link #save(ToolInfo)} 时生成并缓存 Embedding，查询时只需要为 query
 * 生成一次 Embedding，然后使用余弦相似度进行 O(n) 排序。该实现适合几十到几百个 Tool，
 * 可以在不部署向量数据库的情况下补充默认词法 Provider 无法覆盖的同义表达和自然语言召回。</p>
 *
 * <p>用于 Embedding 的文本包含名称、描述、分类、标签和递归参数结构。{@link ToolInfo#getMetadata()}
 * 不会自动参与 Embedding，因为任意业务 Metadata 可能包含敏感信息、内部标识或高噪声字段；需要参与
 * 语义召回的业务词应放在 category、tags 或描述中。</p>
 *
 * <p>工具名称的规范化精确匹配始终优先于纯向量结果，避免模型已经明确给出 Tool 名称时被近义 Tool
 * 覆盖。除此之外，结果按余弦相似度降序排列，同分时按名称排序以保证结果稳定。</p>
 */
public class SemanticToolSearchProvider implements ToolSearchProvider {

    /** 默认最低余弦相似度。 */
    public static final double DEFAULT_MIN_SCORE = 0.2d;

    private final EmbeddingModel embeddingModel;
    private final double minScore;
    /** 同名更新以一个不可变条目原子替换 metadata 与 vector，避免读到混合版本。 */
    private final ConcurrentMap<String, IndexedTool> index = new ConcurrentHashMap<>();

    /**
     * 使用默认最低相似度创建 Provider。
     */
    public SemanticToolSearchProvider(EmbeddingModel embeddingModel) {
        this(embeddingModel, DEFAULT_MIN_SCORE);
    }

    /**
     * 创建 Provider。
     *
     * @param embeddingModel Tool 与查询使用的 Embedding 模型
     * @param minScore       最低余弦相似度，范围 [-1, 1]
     */
    public SemanticToolSearchProvider(EmbeddingModel embeddingModel, double minScore) {
        if (embeddingModel == null) {
            throw new IllegalArgumentException("EmbeddingModel must not be null");
        }
        if (Double.isNaN(minScore) || minScore < -1.0d || minScore > 1.0d) {
            throw new IllegalArgumentException("minScore must be between -1 and 1");
        }
        this.embeddingModel = embeddingModel;
        this.minScore = minScore;
    }

    /**
     * 保存或覆盖 Tool 元数据，并立即刷新对应的缓存 Embedding。
     *
     * <p>先生成向量，再用单个索引条目发布 metadata 与 vector。Embedding 失败时旧索引保持可用；
     * 成功后同名更新对并发搜索线程表现为一次原子替换。</p>
     */
    @Override
    public void save(ToolInfo toolInfo) {
        if (toolInfo == null || !hasText(toolInfo.getName())) {
            throw new IllegalArgumentException("ToolInfo and its name must not be blank");
        }
        float[] vector = embed(searchableText(toolInfo));
        index.put(toolInfo.getName(), new IndexedTool(toolInfo, vector));
    }

    @Override
    public ToolInfo findByName(String name) {
        IndexedTool indexed = name == null ? null : index.get(name);
        return indexed == null ? null : indexed.toolInfo;
    }

    @Override
    public List<ToolInfo> findAll() {
        List<ToolInfo> result = new ArrayList<>();
        for (IndexedTool indexed : index.values()) result.add(indexed.toolInfo);
        result.sort(Comparator.comparing(ToolInfo::getName));
        return result;
    }

    /**
     * 使用余弦相似度搜索 Tool。
     *
     * <p>category 使用规范化后的精确匹配。空 query 不调用 Embedding 模型，直接按名称返回分类范围内
     * 的前 N 个 Tool，与默认内存 Provider 的空查询行为保持一致。</p>
     */
    @Override
    public List<ToolSearchResult> search(ToolSearchRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("ToolSearchRequest must not be null");
        }
        String normalizedQuery = normalize(request.getQuery());
        if (!hasText(normalizedQuery)) {
            return unscoredResults(request);
        }

        float[] queryVector = embed(request.getQuery());
        List<ToolSearchResult> results = new ArrayList<>();
        for (IndexedTool indexed : index.values()) {
            ToolInfo tool = indexed.toolInfo;
            if (!matchesCategory(tool, request.getCategory())) continue;

            boolean exactName = normalize(tool.getName()).equals(normalizedQuery);
            double score = exactName ? 1.0d : cosineSimilarity(queryVector, indexed.vector);
            if (exactName || score >= minScore) {
                results.add(new ToolSearchResult(tool, score,
                    Collections.singletonList(exactName ? "name" : "semantic")));
            }
        }
        results.sort(Comparator
            .comparing(SemanticToolSearchProvider::isExactNameResult)
            .reversed()
            .thenComparing(Comparator.comparingDouble(ToolSearchResult::getScore).reversed())
            .thenComparing(result -> result.getToolInfo().getName()));
        return limit(results, request.getMaxResults());
    }

    @Override
    public boolean remove(String name) {
        return name != null && index.remove(name) != null;
    }

    @Override
    public void clear() {
        index.clear();
    }

    /** @return 当前最低余弦相似度。 */
    public double getMinScore() {
        return minScore;
    }

    private List<ToolSearchResult> unscoredResults(ToolSearchRequest request) {
        List<ToolInfo> candidates = new ArrayList<>();
        for (IndexedTool indexed : index.values()) {
            if (matchesCategory(indexed.toolInfo, request.getCategory())) candidates.add(indexed.toolInfo);
        }
        candidates.sort(Comparator.comparing(ToolInfo::getName));
        List<ToolSearchResult> results = new ArrayList<>();
        for (ToolInfo tool : candidates) {
            results.add(new ToolSearchResult(tool, 0.0d, Collections.<String>emptyList()));
        }
        return limit(results, request.getMaxResults());
    }

    private static boolean isExactNameResult(ToolSearchResult result) {
        return !result.getMatchedFields().isEmpty() && "name".equals(result.getMatchedFields().get(0));
    }

    private static boolean matchesCategory(ToolInfo tool, String category) {
        return !hasText(category) || normalize(category).equals(normalize(tool.getCategory()));
    }

    private static <T> List<T> limit(List<T> values, int maxResults) {
        return values.size() > maxResults
            ? new ArrayList<>(values.subList(0, maxResults)) : values;
    }

    private float[] embed(String text) {
        VectorData data = embeddingModel.embed(text);
        if (data == null || data.getVector() == null || data.getVector().length == 0) {
            throw new IllegalStateException("EmbeddingModel returned an empty vector");
        }
        return data.getVector().clone();
    }

    /**
     * 将 Tool 元数据构造成稳定且有字段边界的可检索文本。
     */
    static String searchableText(ToolInfo tool) {
        StringBuilder text = new StringBuilder();
        appendField(text, "name", tool.getName());
        appendField(text, "description", tool.getDescription());
        appendField(text, "category", tool.getCategory());
        if (!tool.getTags().isEmpty()) {
            appendField(text, "tags", String.join(", ", tool.getTags()));
        }
        appendParameters(text, tool.getParameters(), "parameters");
        return text.toString();
    }

    private static void appendParameters(StringBuilder text, List<ToolParameterInfo> parameters, String path) {
        for (ToolParameterInfo parameter : parameters) {
            if (parameter == null) continue;
            String currentPath = path + "." + safe(parameter.getName());
            StringBuilder value = new StringBuilder();
            if (hasText(parameter.getName())) value.append(parameter.getName());
            if (hasText(parameter.getType())) value.append(" type=").append(parameter.getType());
            if (hasText(parameter.getDescription())) value.append(" ").append(parameter.getDescription());
            if (parameter.isRequired()) value.append(" required");
            appendField(text, currentPath, value.toString());
            appendParameters(text, parameter.getChildren(), currentPath);
        }
    }

    private static void appendField(StringBuilder text, String field, String value) {
        if (!hasText(value)) return;
        if (text.length() > 0) text.append('\n');
        text.append(field).append(": ").append(value.trim());
    }

    private static double cosineSimilarity(float[] left, float[] right) {
        if (left.length != right.length) {
            throw new IllegalStateException("Embedding dimensions do not match: "
                + left.length + " != " + right.length);
        }
        double dot = 0.0d;
        double leftNorm = 0.0d;
        double rightNorm = 0.0d;
        for (int i = 0; i < left.length; i++) {
            dot += (double) left[i] * right[i];
            leftNorm += (double) left[i] * left[i];
            rightNorm += (double) right[i] * right[i];
        }
        if (leftNorm == 0.0d || rightNorm == 0.0d) return 0.0d;
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.replaceAll("([\\p{Ll}\\d])(\\p{Lu})", "$1 $2")
            .replaceAll("[^\\p{L}\\p{N}]+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /** metadata 与 vector 的不可变索引快照。 */
    private static final class IndexedTool {
        private final ToolInfo toolInfo;
        private final float[] vector;

        private IndexedTool(ToolInfo toolInfo, float[] vector) {
            this.toolInfo = toolInfo;
            this.vector = vector;
        }
    }
}

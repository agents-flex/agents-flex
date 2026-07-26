/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.store.infinity;

/** Infinity 稠密向量检索和 HNSW 索引支持的距离度量。 */
public enum InfinitySimilarity {
    /** 余弦相似度，适合大多数文本 Embedding。 */
    COSINE("cosine", "SIMILARITY"),
    /** 内积；调用方通常需要先对向量做归一化。 */
    IP("ip", "SIMILARITY"),
    /** 欧氏距离；模块会转换为越大越相似的 {@code 1 / (1 + distance)} score。 */
    L2("l2", "DISTANCE");

    private final String metricName;
    private final String resultField;

    InfinitySimilarity(String metricName, String resultField) {
        this.metricName = metricName;
        this.resultField = resultField;
    }

    public String getMetricName() { return metricName; }
    public String getResultField() { return resultField; }
}

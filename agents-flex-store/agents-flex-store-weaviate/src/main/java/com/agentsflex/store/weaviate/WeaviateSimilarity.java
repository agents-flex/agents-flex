/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.store.weaviate;

/** Weaviate HNSW 向量索引支持的距离度量。 */
public enum WeaviateSimilarity {
    /** 余弦距离，适合多数文本 Embedding，也是默认选项。 */
    COSINE("cosine"),
    /** 点积距离，通常要求模型输出已经归一化。 */
    DOT("dot"),
    /** 欧氏距离。 */
    L2_SQUARED("l2-squared"),
    /** 曼哈顿距离。 */
    MANHATTAN("manhattan"),
    /** 汉明距离，适用于具有离散意义的向量。 */
    HAMMING("hamming");

    private final String distanceName;

    WeaviateSimilarity(String distanceName) {
        this.distanceName = distanceName;
    }

    public String getDistanceName() {
        return distanceName;
    }
}

/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.store.cassandra;

/** Cassandra 5.x SAI 向量索引支持的相似度函数。 */
public enum CassandraSimilarity {
    /** 余弦相似度，适合大多数文本 Embedding。 */
    COSINE("cosine", "similarity_cosine"),
    /** 点积相似度，通常要求调用方按模型约定归一化向量。 */
    DOT_PRODUCT("dot_product", "similarity_dot_product"),
    /** 欧氏距离对应的 Cassandra 相似度函数。 */
    EUCLIDEAN("euclidean", "similarity_euclidean");

    private final String indexValue;
    private final String functionName;

    CassandraSimilarity(String indexValue, String functionName) {
        this.indexValue = indexValue;
        this.functionName = functionName;
    }

    String getIndexValue() {
        return indexValue;
    }

    String getFunctionName() {
        return functionName;
    }
}

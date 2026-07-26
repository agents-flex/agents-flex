/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.store.clickhouse;

/** ClickHouse 向量检索支持的距离或相似度函数。 */
public enum ClickHouseSimilarity {
    /** 余弦距离，结果转换为 {@code 1 - cosineDistance}。 */
    COSINE("cosineDistance", "ASC", true),
    /** 欧氏距离，结果转换为 {@code 1 / (1 + L2Distance)}。 */
    L2("L2Distance", "ASC", true),
    /** 内积直接作为 score；ClickHouse 25.8 的 vector_similarity 索引暂不支持，因此采用精确排序。 */
    DOT_PRODUCT("dotProduct", "DESC", false);

    private final String functionName;
    private final String order;
    private final boolean vectorIndexSupported;

    ClickHouseSimilarity(String functionName, String order, boolean vectorIndexSupported) {
        this.functionName = functionName;
        this.order = order;
        this.vectorIndexSupported = vectorIndexSupported;
    }

    public String getFunctionName() { return functionName; }
    public String getOrder() { return order; }
    public boolean isVectorIndexSupported() { return vectorIndexSupported; }

    String scoreExpression(String vectorExpression) {
        if (this == COSINE) return "1 - " + vectorExpression;
        if (this == L2) return "1 / (1 + " + vectorExpression + ")";
        return vectorExpression;
    }
}

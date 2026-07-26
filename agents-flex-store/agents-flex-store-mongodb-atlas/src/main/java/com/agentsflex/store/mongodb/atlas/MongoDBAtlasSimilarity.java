/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.store.mongodb.atlas;

/**
 * MongoDB Atlas Vector Search 支持的向量相似度算法。
 */
public enum MongoDBAtlasSimilarity {
    /** 余弦相似度，适合多数文本 Embedding。 */
    COSINE("cosine"),
    /** 点积，相应模型通常要求向量已经归一化。 */
    DOT_PRODUCT("dotProduct"),
    /** 欧氏距离。 */
    EUCLIDEAN("euclidean");

    private final String atlasValue;

    MongoDBAtlasSimilarity(String atlasValue) {
        this.atlasValue = atlasValue;
    }

    public String getAtlasValue() {
        return atlasValue;
    }
}

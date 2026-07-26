/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.store.mongodb.atlas;

import com.agentsflex.core.store.DocumentStoreConfig;
import com.agentsflex.core.util.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MongoDB Atlas Vector Search 连接和索引配置。
 *
 * <p>Atlas Vector Search 的预过滤字段必须出现在向量索引定义中，因此业务代码使用
 * {@code SearchWrapper} 查询哪些 metadata 字段，就应通过 {@link #setFilterFields(List)}
 * 声明哪些字段。字段可以写成 {@code category} 或 {@code metadataMap.category}。</p>
 */
public class MongoDBAtlasVectorStoreConfig implements DocumentStoreConfig {

    private String connectionString = "mongodb://127.0.0.1:27018/?directConnection=true";
    private String databaseName = "agents_flex";
    private String defaultCollectionName = "agents_flex_default";
    private String vectorIndexName = "agents_flex_vector_index";
    private String vectorField = "vector";
    private MongoDBAtlasSimilarity similarity = MongoDBAtlasSimilarity.COSINE;
    private List<String> filterFields = Collections.emptyList();
    private boolean autoCreateCollection = true;
    private boolean autoCreateVectorIndex = true;
    private int vectorDimension;
    private int numCandidatesMultiplier = 10;
    private long indexReadyTimeoutMillis = 120_000L;
    private boolean waitForSearchIndexing = true;

    public String getConnectionString() {
        return connectionString;
    }

    public void setConnectionString(String connectionString) {
        this.connectionString = connectionString;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getDefaultCollectionName() {
        return defaultCollectionName;
    }

    public void setDefaultCollectionName(String defaultCollectionName) {
        this.defaultCollectionName = defaultCollectionName;
    }

    public String getVectorIndexName() {
        return vectorIndexName;
    }

    public void setVectorIndexName(String vectorIndexName) {
        this.vectorIndexName = vectorIndexName;
    }

    public String getVectorField() {
        return vectorField;
    }

    public void setVectorField(String vectorField) {
        this.vectorField = vectorField;
    }

    public MongoDBAtlasSimilarity getSimilarity() {
        return similarity;
    }

    public void setSimilarity(MongoDBAtlasSimilarity similarity) {
        this.similarity = similarity;
    }

    public List<String> getFilterFields() {
        return filterFields;
    }

    /** 复制调用方集合，避免配置完成后被外部修改。 */
    public void setFilterFields(List<String> filterFields) {
        this.filterFields = filterFields == null
            ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(filterFields));
    }

    public boolean isAutoCreateCollection() {
        return autoCreateCollection;
    }

    public void setAutoCreateCollection(boolean autoCreateCollection) {
        this.autoCreateCollection = autoCreateCollection;
    }

    public boolean isAutoCreateVectorIndex() {
        return autoCreateVectorIndex;
    }

    public void setAutoCreateVectorIndex(boolean autoCreateVectorIndex) {
        this.autoCreateVectorIndex = autoCreateVectorIndex;
    }

    public int getVectorDimension() {
        return vectorDimension;
    }

    public void setVectorDimension(int vectorDimension) {
        if (vectorDimension < 0) {
            throw new IllegalArgumentException("vectorDimension cannot be negative");
        }
        this.vectorDimension = vectorDimension;
    }

    public int getNumCandidatesMultiplier() {
        return numCandidatesMultiplier;
    }

    public void setNumCandidatesMultiplier(int numCandidatesMultiplier) {
        if (numCandidatesMultiplier < 1) {
            throw new IllegalArgumentException("numCandidatesMultiplier must be at least 1");
        }
        this.numCandidatesMultiplier = numCandidatesMultiplier;
    }

    public long getIndexReadyTimeoutMillis() {
        return indexReadyTimeoutMillis;
    }

    public void setIndexReadyTimeoutMillis(long indexReadyTimeoutMillis) {
        if (indexReadyTimeoutMillis < 1) {
            throw new IllegalArgumentException("indexReadyTimeoutMillis must be positive");
        }
        this.indexReadyTimeoutMillis = indexReadyTimeoutMillis;
    }

    public boolean isWaitForSearchIndexing() {
        return waitForSearchIndexing;
    }

    /**
     * 设置写入、更新和删除返回前是否等待 Atlas Search 索引同步。
     * 关闭后写吞吐更高，但紧随其后的向量查询可能暂时看到旧数据。
     */
    public void setWaitForSearchIndexing(boolean waitForSearchIndexing) {
        this.waitForSearchIndexing = waitForSearchIndexing;
    }

    @Override
    public boolean checkAvailable() {
        return StringUtil.allHasText(connectionString, databaseName, defaultCollectionName,
            vectorIndexName, vectorField) && similarity != null;
    }
}

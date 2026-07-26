/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.store.infinity;

import com.agentsflex.core.store.DocumentStoreConfig;
import com.agentsflex.core.util.StringUtil;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Infinity 连接、数据库、Collection schema 与 HNSW 参数配置。 */
public class InfinityVectorStoreConfig implements DocumentStoreConfig {
    /** Infinity HTTP API 地址，默认端口为 23820。 */
    private String serverUrl = "http://127.0.0.1:23820";
    /** 可选 Bearer token；本地默认部署通常不启用认证。 */
    private String apiKey;
    /** 全部 Collection 所在的 Infinity database。 */
    private String databaseName = "default_db";
    /** StoreOptions 未指定 Collection 时使用的表名。 */
    private String defaultCollectionName = "documents";
    /** vector 列维度。Infinity 建表时必须提前确定，因此必须大于 0。 */
    private int vectorDimension = 1024;
    /** HNSW 索引与查询使用的距离度量。 */
    private InfinitySimilarity similarity = InfinitySimilarity.COSINE;
    /** 是否自动创建 database。 */
    private boolean autoCreateDatabase = true;
    /** 是否自动创建 Collection 对应的表。 */
    private boolean autoCreateCollection = true;
    /** 是否按文档值推断并新增 metadata 列。 */
    private boolean autoCreateMetadataColumns = true;
    /** 是否为向量列创建 HNSW 索引。 */
    private boolean autoCreateVectorIndex = true;
    /** HNSW 图中每个节点的最大邻居数。 */
    private int hnswM = 16;
    /** HNSW 建索引时的候选队列大小。 */
    private int hnswEfConstruction = 50;
    /** 单次 HTTP 请求超时。 */
    private long requestTimeoutMillis = 30000;
    /** 生产环境建议显式声明的 metadata schema。 */
    private Map<String, InfinityMetadataType> metadataFieldTypes = Collections.emptyMap();

    public String getServerUrl() { return serverUrl; }
    public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }
    public String getDefaultCollectionName() { return defaultCollectionName; }
    public void setDefaultCollectionName(String defaultCollectionName) { this.defaultCollectionName = defaultCollectionName; }
    public int getVectorDimension() { return vectorDimension; }
    public void setVectorDimension(int vectorDimension) { this.vectorDimension = vectorDimension; }
    public InfinitySimilarity getSimilarity() { return similarity; }
    public void setSimilarity(InfinitySimilarity similarity) { this.similarity = similarity; }
    public boolean isAutoCreateDatabase() { return autoCreateDatabase; }
    public void setAutoCreateDatabase(boolean autoCreateDatabase) { this.autoCreateDatabase = autoCreateDatabase; }
    public boolean isAutoCreateCollection() { return autoCreateCollection; }
    public void setAutoCreateCollection(boolean autoCreateCollection) { this.autoCreateCollection = autoCreateCollection; }
    public boolean isAutoCreateMetadataColumns() { return autoCreateMetadataColumns; }
    public void setAutoCreateMetadataColumns(boolean autoCreateMetadataColumns) { this.autoCreateMetadataColumns = autoCreateMetadataColumns; }
    public boolean isAutoCreateVectorIndex() { return autoCreateVectorIndex; }
    public void setAutoCreateVectorIndex(boolean autoCreateVectorIndex) { this.autoCreateVectorIndex = autoCreateVectorIndex; }
    public int getHnswM() { return hnswM; }
    public void setHnswM(int hnswM) { this.hnswM = hnswM; }
    public int getHnswEfConstruction() { return hnswEfConstruction; }
    public void setHnswEfConstruction(int hnswEfConstruction) { this.hnswEfConstruction = hnswEfConstruction; }
    public long getRequestTimeoutMillis() { return requestTimeoutMillis; }
    public void setRequestTimeoutMillis(long requestTimeoutMillis) { this.requestTimeoutMillis = requestTimeoutMillis; }
    public Map<String, InfinityMetadataType> getMetadataFieldTypes() { return metadataFieldTypes; }

    /** 防御性复制 schema，防止 Store 构造后被调用方修改。 */
    public void setMetadataFieldTypes(Map<String, InfinityMetadataType> metadataFieldTypes) {
        this.metadataFieldTypes = metadataFieldTypes == null ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(metadataFieldTypes));
    }

    URI serverUri() {
        URI uri = URI.create(serverUrl);
        if (!StringUtil.hasText(uri.getScheme()) || !StringUtil.hasText(uri.getHost())) {
            throw new IllegalArgumentException("Infinity serverUrl must contain scheme and host");
        }
        return uri;
    }

    @Override
    public boolean checkAvailable() {
        try {
            serverUri();
            return StringUtil.allHasText(databaseName, defaultCollectionName)
                && vectorDimension > 0 && similarity != null && hnswM > 0
                && hnswEfConstruction > 0 && requestTimeoutMillis > 0;
        } catch (RuntimeException exception) {
            return false;
        }
    }
}

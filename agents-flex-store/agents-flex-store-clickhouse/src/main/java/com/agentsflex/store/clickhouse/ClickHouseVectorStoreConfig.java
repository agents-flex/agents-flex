/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.store.clickhouse;

import com.agentsflex.core.store.DocumentStoreConfig;
import com.agentsflex.core.util.StringUtil;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** ClickHouse JDBC、数据库、表和 vector_similarity HNSW 索引配置。 */
public class ClickHouseVectorStoreConfig implements DocumentStoreConfig {
    /** ClickHouse HTTP/JDBC 主机。 */
    private String host = "127.0.0.1";
    /** ClickHouse HTTP 端口，默认服务器端口为 8123。 */
    private int port = 8123;
    /** 连接用户。 */
    private String username = "default";
    /** 连接密码，本地默认用户可以为空。 */
    private String password = "";
    /** Collection 表所在数据库。 */
    private String databaseName = "default";
    /** StoreOptions 未覆盖时使用的默认表名。 */
    private String defaultCollectionName = "documents";
    /** Array(Float32) 的固定长度，也是 HNSW 索引维度。 */
    private int vectorDimension = 1024;
    /** 查询和向量索引使用的距离函数。 */
    private ClickHouseSimilarity similarity = ClickHouseSimilarity.COSINE;
    /** 是否自动创建 database。 */
    private boolean autoCreateDatabase = true;
    /** 是否自动创建 Collection 表。 */
    private boolean autoCreateCollection = true;
    /** 是否创建 ClickHouse 25.8+ vector_similarity HNSW 索引；DOT_PRODUCT 会自动退化为精确检索。 */
    private boolean autoCreateVectorIndex = true;
    /** HNSW 图每层最大连接数 M。 */
    private int hnswM = 32;
    /** HNSW 构建候选队列大小 ef_construction。 */
    private int hnswEfConstruction = 128;
    /** HNSW 查询候选队列大小 ef_search。 */
    private int hnswEfSearch = 256;
    /** 索引向量量化类型，默认使用官方推荐的 bf16。 */
    private String quantization = "bf16";
    /** vector_similarity 查询允许的最大 LIMIT。 */
    private int maxVectorSearchResults = 100;
    /** JDBC socket/connect 超时，单位毫秒。 */
    private long requestTimeoutMillis = 30000;
    /** 传给 JDBC URL 的额外属性，setter 会做防御性复制。 */
    private Map<String, String> properties = Collections.emptyMap();

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }
    public String getDefaultCollectionName() { return defaultCollectionName; }
    public void setDefaultCollectionName(String defaultCollectionName) { this.defaultCollectionName = defaultCollectionName; }
    public int getVectorDimension() { return vectorDimension; }
    public void setVectorDimension(int vectorDimension) { this.vectorDimension = vectorDimension; }
    public ClickHouseSimilarity getSimilarity() { return similarity; }
    public void setSimilarity(ClickHouseSimilarity similarity) { this.similarity = similarity; }
    public boolean isAutoCreateDatabase() { return autoCreateDatabase; }
    public void setAutoCreateDatabase(boolean autoCreateDatabase) { this.autoCreateDatabase = autoCreateDatabase; }
    public boolean isAutoCreateCollection() { return autoCreateCollection; }
    public void setAutoCreateCollection(boolean autoCreateCollection) { this.autoCreateCollection = autoCreateCollection; }
    public boolean isAutoCreateVectorIndex() { return autoCreateVectorIndex; }
    public void setAutoCreateVectorIndex(boolean autoCreateVectorIndex) { this.autoCreateVectorIndex = autoCreateVectorIndex; }
    public int getHnswM() { return hnswM; }
    public void setHnswM(int hnswM) { this.hnswM = hnswM; }
    public int getHnswEfConstruction() { return hnswEfConstruction; }
    public void setHnswEfConstruction(int value) { this.hnswEfConstruction = value; }
    public int getHnswEfSearch() { return hnswEfSearch; }
    public void setHnswEfSearch(int hnswEfSearch) { this.hnswEfSearch = hnswEfSearch; }
    public String getQuantization() { return quantization; }
    public void setQuantization(String quantization) { this.quantization = quantization; }
    public int getMaxVectorSearchResults() { return maxVectorSearchResults; }
    public void setMaxVectorSearchResults(int value) { this.maxVectorSearchResults = value; }
    public long getRequestTimeoutMillis() { return requestTimeoutMillis; }
    public void setRequestTimeoutMillis(long value) { this.requestTimeoutMillis = value; }
    public Map<String, String> getProperties() { return properties; }
    public void setProperties(Map<String, String> properties) {
        this.properties = properties == null ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    @Override
    public boolean checkAvailable() {
        return StringUtil.allHasText(host, username, databaseName, defaultCollectionName)
            && port > 0 && port <= 65535 && vectorDimension > 0 && similarity != null
            && hnswM > 0 && hnswEfConstruction > 0 && hnswEfSearch > 0
            && maxVectorSearchResults > 0 && requestTimeoutMillis > 0
            && isSupportedQuantization(quantization);
    }

    private boolean isSupportedQuantization(String value) {
        return "f64".equals(value) || "f32".equals(value) || "f16".equals(value)
            || "bf16".equals(value) || "i8".equals(value) || "b1".equals(value);
    }
}

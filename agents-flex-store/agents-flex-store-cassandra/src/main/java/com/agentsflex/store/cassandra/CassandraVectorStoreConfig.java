/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.store.cassandra;

import com.agentsflex.core.store.DocumentStoreConfig;
import com.agentsflex.core.util.StringUtil;

import java.util.LinkedHashMap;
import java.util.Map;

/** Apache Cassandra 连接、建表、SAI 索引和 metadata schema 配置。 */
public class CassandraVectorStoreConfig implements DocumentStoreConfig {

    /** 一个或多个原生协议地址，多个地址使用英文逗号分隔。 */
    private String contactPoint = "127.0.0.1:9042";
    /** Driver 负载均衡使用的本地数据中心，必须与节点拓扑一致。 */
    private String localDatacenter = "datacenter1";
    /** 可选用户名；为空时不启用密码认证。 */
    private String username;
    /** 可选密码，生产环境应从安全配置中心注入。 */
    private String password;
    /** 全部 Collection 所在的 Cassandra keyspace。 */
    private String keyspace = "agents_flex";
    /** StoreOptions 未覆盖时使用的默认表名。 */
    private String defaultCollectionName = "documents";
    /** vector<float, N> 的固定维度。 */
    private int vectorDimension = 1024;
    /** 建立向量 SAI 和计算 score 时使用的相似度。 */
    private CassandraSimilarity similarity = CassandraSimilarity.COSINE;
    /** 是否在启动时自动创建 keyspace。 */
    private boolean autoCreateKeyspace = true;
    /** 是否在首次访问 Collection 时自动创建表和 SAI。 */
    private boolean autoCreateCollection = true;
    /** 是否按写入值推断并新增 metadata 列及其 SAI。 */
    private boolean autoCreateMetadataColumns = true;
    /** 本地开发 SimpleStrategy keyspace 的副本数。 */
    private int replicationFactor = 1;
    /** DDL 请求以及等待 schema agreement 的最长时间。 */
    private long schemaAgreementTimeoutMillis = 30000;
    /** 普通读写和 schema 元数据查询的请求超时。 */
    private long requestTimeoutMillis = 10000;
    /** 生产环境建议显式声明的 metadata 字段类型。 */
    private Map<String, CassandraMetadataType> metadataFieldTypes = new LinkedHashMap<>();

    public String getContactPoint() { return contactPoint; }
    public void setContactPoint(String contactPoint) { this.contactPoint = contactPoint; }
    public String getLocalDatacenter() { return localDatacenter; }
    public void setLocalDatacenter(String localDatacenter) { this.localDatacenter = localDatacenter; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getKeyspace() { return keyspace; }
    public void setKeyspace(String keyspace) { this.keyspace = keyspace; }
    public String getDefaultCollectionName() { return defaultCollectionName; }
    public void setDefaultCollectionName(String defaultCollectionName) { this.defaultCollectionName = defaultCollectionName; }
    public int getVectorDimension() { return vectorDimension; }
    public void setVectorDimension(int vectorDimension) { this.vectorDimension = vectorDimension; }
    public CassandraSimilarity getSimilarity() { return similarity; }
    public void setSimilarity(CassandraSimilarity similarity) { this.similarity = similarity; }
    public boolean isAutoCreateKeyspace() { return autoCreateKeyspace; }
    public void setAutoCreateKeyspace(boolean autoCreateKeyspace) { this.autoCreateKeyspace = autoCreateKeyspace; }
    public boolean isAutoCreateCollection() { return autoCreateCollection; }
    public void setAutoCreateCollection(boolean autoCreateCollection) { this.autoCreateCollection = autoCreateCollection; }
    public boolean isAutoCreateMetadataColumns() { return autoCreateMetadataColumns; }
    public void setAutoCreateMetadataColumns(boolean autoCreateMetadataColumns) { this.autoCreateMetadataColumns = autoCreateMetadataColumns; }
    public int getReplicationFactor() { return replicationFactor; }
    public void setReplicationFactor(int replicationFactor) { this.replicationFactor = replicationFactor; }
    public long getSchemaAgreementTimeoutMillis() { return schemaAgreementTimeoutMillis; }
    public void setSchemaAgreementTimeoutMillis(long schemaAgreementTimeoutMillis) { this.schemaAgreementTimeoutMillis = schemaAgreementTimeoutMillis; }
    public long getRequestTimeoutMillis() { return requestTimeoutMillis; }
    public void setRequestTimeoutMillis(long requestTimeoutMillis) { this.requestTimeoutMillis = requestTimeoutMillis; }
    public Map<String, CassandraMetadataType> getMetadataFieldTypes() { return new LinkedHashMap<>(metadataFieldTypes); }
    public void setMetadataFieldTypes(Map<String, CassandraMetadataType> metadataFieldTypes) {
        this.metadataFieldTypes = metadataFieldTypes == null
            ? new LinkedHashMap<>() : new LinkedHashMap<>(metadataFieldTypes);
    }

    @Override
    public boolean checkAvailable() {
        return StringUtil.allHasText(contactPoint, localDatacenter, keyspace, defaultCollectionName)
            && vectorDimension > 0 && replicationFactor > 0 && similarity != null
            && schemaAgreementTimeoutMillis > 0 && requestTimeoutMillis > 0;
    }
}

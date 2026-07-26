/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.store.weaviate;

import com.agentsflex.core.store.DocumentStoreConfig;
import com.agentsflex.core.util.StringUtil;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Weaviate 连接、Collection 和 schema 配置。 */
public class WeaviateVectorStoreConfig implements DocumentStoreConfig {

    private String serverUrl = "http://127.0.0.1:8082";
    private String apiKey;
    private String defaultCollectionName = "AgentsFlexDocuments";
    private WeaviateSimilarity similarity = WeaviateSimilarity.COSINE;
    private boolean autoCreateCollection = true;
    private boolean autoCreateMetadataProperties = true;
    private int vectorDimension;
    private int timeoutSeconds = 60;
    private Map<String, WeaviateMetadataType> metadataFieldTypes = Collections.emptyMap();

    public String getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getDefaultCollectionName() {
        return defaultCollectionName;
    }

    public void setDefaultCollectionName(String defaultCollectionName) {
        this.defaultCollectionName = defaultCollectionName;
    }

    public WeaviateSimilarity getSimilarity() {
        return similarity;
    }

    public void setSimilarity(WeaviateSimilarity similarity) {
        this.similarity = similarity;
    }

    public boolean isAutoCreateCollection() {
        return autoCreateCollection;
    }

    public void setAutoCreateCollection(boolean autoCreateCollection) {
        this.autoCreateCollection = autoCreateCollection;
    }

    public boolean isAutoCreateMetadataProperties() {
        return autoCreateMetadataProperties;
    }

    public void setAutoCreateMetadataProperties(boolean autoCreateMetadataProperties) {
        this.autoCreateMetadataProperties = autoCreateMetadataProperties;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public int getVectorDimension() {
        return vectorDimension;
    }

    /** 设置预期向量维度；0 表示由首次写入决定。 */
    public void setVectorDimension(int vectorDimension) {
        if (vectorDimension < 0) {
            throw new IllegalArgumentException("vectorDimension cannot be negative");
        }
        this.vectorDimension = vectorDimension;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException("timeoutSeconds must be positive");
        }
        this.timeoutSeconds = timeoutSeconds;
    }

    public Map<String, WeaviateMetadataType> getMetadataFieldTypes() {
        return metadataFieldTypes;
    }

    /** 防御性复制字段 schema，避免 Store 创建后被调用方集合修改。 */
    public void setMetadataFieldTypes(Map<String, WeaviateMetadataType> metadataFieldTypes) {
        this.metadataFieldTypes = metadataFieldTypes == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(metadataFieldTypes));
    }

    URI serverUri() {
        try {
            URI uri = URI.create(serverUrl);
            if (!StringUtil.hasText(uri.getScheme()) || !StringUtil.hasText(uri.getHost())) {
                throw new IllegalArgumentException("Weaviate serverUrl must contain scheme and host");
            }
            return uri;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid Weaviate serverUrl: " + serverUrl, exception);
        }
    }

    @Override
    public boolean checkAvailable() {
        if (!StringUtil.allHasText(serverUrl, defaultCollectionName) || similarity == null) {
            return false;
        }
        try {
            serverUri();
            WeaviateVectorStore.validateCollectionName(defaultCollectionName);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }
}

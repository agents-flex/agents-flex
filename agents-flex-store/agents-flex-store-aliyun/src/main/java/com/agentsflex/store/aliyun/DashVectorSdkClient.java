/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentsflex.store.aliyun;

import com.agentsflex.core.store.exception.StoreException;
import com.aliyun.dashvector.DashVectorClient;
import com.aliyun.dashvector.DashVectorClientConfig;
import com.aliyun.dashvector.DashVectorCollection;
import com.aliyun.dashvector.models.DocOpResult;
import com.aliyun.dashvector.models.requests.DeleteDocRequest;
import com.aliyun.dashvector.models.requests.QueryDocRequest;
import com.aliyun.dashvector.models.requests.UpdateDocRequest;
import com.aliyun.dashvector.models.requests.UpsertDocRequest;
import com.aliyun.dashvector.models.responses.Response;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 使用阿里云官方 Java SDK 访问 DashVector。 */
final class DashVectorSdkClient implements AliyunVectorClient {

    private final DashVectorClient client;
    private final ConcurrentMap<String, DashVectorCollection> collections = new ConcurrentHashMap<>();

    DashVectorSdkClient(AliyunVectorStoreConfig config) {
        if (config == null || !config.checkAvailable()) {
            throw new IllegalArgumentException(
                "DashVector endpoint, apiKey, timeout and defaultCollectionName are required.");
        }
        DashVectorClientConfig sdkConfig = DashVectorClientConfig.builder()
            .endpoint(config.getEndpoint())
            .apiKey(config.getApiKey())
            .timeout(config.getTimeout())
            .build();
        this.client = new DashVectorClient(sdkConfig);
    }

    DashVectorSdkClient(DashVectorClient client) {
        if (client == null) {
            throw new IllegalArgumentException("DashVectorClient must not be null.");
        }
        this.client = client;
    }

    @Override
    public Response<List<DocOpResult>> upsert(String collectionName, UpsertDocRequest request) {
        return collection(collectionName).upsert(request);
    }

    @Override
    public Response<List<DocOpResult>> update(String collectionName, UpdateDocRequest request) {
        return collection(collectionName).update(request);
    }

    @Override
    public Response<List<DocOpResult>> delete(String collectionName, DeleteDocRequest request) {
        return collection(collectionName).delete(request);
    }

    @Override
    public QueryResult query(String collectionName, QueryDocRequest request) {
        DashVectorCollection collection = collection(collectionName);
        return new QueryResult(collection.query(request), collection.getCollectionMeta().getMetric());
    }

    private DashVectorCollection collection(String collectionName) {
        if (collectionName == null || collectionName.trim().isEmpty()) {
            throw new IllegalArgumentException("DashVector collection name must not be blank.");
        }
        DashVectorCollection cached = collections.get(collectionName);
        if (cached != null) {
            return cached;
        }

        DashVectorCollection created = client.get(collectionName);
        if (created == null || !Boolean.TRUE.equals(created.isSuccess())) {
            String detail = created == null
                ? "empty SDK response"
                : "code=" + created.getCode() + ", message=" + created.getMessage()
                    + ", requestId=" + created.getRequestId();
            throw new StoreException(
                "Failed to get DashVector collection '" + collectionName + "': " + detail);
        }
        DashVectorCollection existing = collections.putIfAbsent(collectionName, created);
        return existing == null ? created : existing;
    }

    @Override
    public void close() {
        collections.clear();
        client.close();
    }
}

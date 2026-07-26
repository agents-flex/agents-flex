/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentsflex.store.qcloud;

import com.tencent.tcvectordb.client.VectorDBClient;
import com.tencent.tcvectordb.model.Document;
import com.tencent.tcvectordb.model.param.database.ConnectParam;
import com.tencent.tcvectordb.model.param.dml.DeleteParam;
import com.tencent.tcvectordb.model.param.dml.InsertParam;
import com.tencent.tcvectordb.model.param.dml.QueryParam;
import com.tencent.tcvectordb.model.param.dml.SearchByVectorParam;
import com.tencent.tcvectordb.model.param.dml.UpdateParam;
import com.tencent.tcvectordb.model.param.entity.AffectRes;
import com.tencent.tcvectordb.model.param.enums.ReadConsistencyEnum;

import java.util.List;

/** 使用腾讯云官方 Java SDK 访问 VectorDB。 */
final class TencentVectorSdkClient implements QCloudVectorClient {

    private final VectorDBClient client;

    TencentVectorSdkClient(QCloudVectorStoreConfig config) {
        if (config == null || !config.checkAvailable()) {
            throw new IllegalArgumentException("Tencent VectorDB configuration is incomplete or invalid.");
        }
        ConnectParam connectParam = ConnectParam.newBuilder()
            .withUrl(config.getHost())
            .withUsername(config.getAccount())
            .withKey(config.getApiKey())
            .withTimeout(config.getTimeout())
            .withConnectTimeout(config.getConnectTimeout())
            .withMaxIdleConnections(config.getMaxIdleConnections())
            .build();
        this.client = new VectorDBClient(connectParam, ReadConsistencyEnum.EVENTUAL_CONSISTENCY);
    }

    TencentVectorSdkClient(VectorDBClient client) {
        if (client == null) {
            throw new IllegalArgumentException("VectorDBClient must not be null.");
        }
        this.client = client;
    }

    @Override
    public AffectRes upsert(String database, String collection, InsertParam param) {
        return client.upsert(database, collection, param);
    }

    @Override
    public AffectRes delete(String database, String collection, DeleteParam param) {
        return client.delete(database, collection, param);
    }

    @Override
    public AffectRes update(String database, String collection, UpdateParam param, Document document) {
        return client.update(database, collection, param, document);
    }

    @Override
    public List<List<Document>> search(String database, String collection, SearchByVectorParam param) {
        return client.search(database, collection, param);
    }

    @Override
    public List<Document> query(String database, String collection, QueryParam param) {
        return client.query(database, collection, param);
    }

    @Override
    public void close() {
        client.close();
    }
}

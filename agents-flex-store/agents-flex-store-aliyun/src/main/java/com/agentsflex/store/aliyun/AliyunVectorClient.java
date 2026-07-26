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

import com.aliyun.dashvector.models.Doc;
import com.aliyun.dashvector.models.DocOpResult;
import com.aliyun.dashvector.models.requests.DeleteDocRequest;
import com.aliyun.dashvector.models.requests.QueryDocRequest;
import com.aliyun.dashvector.models.requests.UpdateDocRequest;
import com.aliyun.dashvector.models.requests.UpsertDocRequest;
import com.aliyun.dashvector.models.responses.Response;
import com.aliyun.dashvector.proto.CollectionInfo;

import java.util.List;

/** 为官方 DashVector SDK 提供可测试的最小调用边界。 */
interface AliyunVectorClient extends AutoCloseable {

    Response<List<DocOpResult>> upsert(String collectionName, UpsertDocRequest request);

    Response<List<DocOpResult>> update(String collectionName, UpdateDocRequest request);

    Response<List<DocOpResult>> delete(String collectionName, DeleteDocRequest request);

    QueryResult query(String collectionName, QueryDocRequest request);

    @Override
    void close();

    final class QueryResult {
        private final Response<List<Doc>> response;
        private final CollectionInfo.Metric metric;

        QueryResult(Response<List<Doc>> response, CollectionInfo.Metric metric) {
            this.response = response;
            this.metric = metric;
        }

        Response<List<Doc>> getResponse() {
            return response;
        }

        CollectionInfo.Metric getMetric() {
            return metric;
        }
    }
}

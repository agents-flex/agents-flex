/*
 *  Copyright (c) 2022-2023, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.agentsflex;

import com.agentsflex.document.Document;
import com.agentsflex.store.DocumentStore;
import com.agentsflex.store.SearchWrapper;
import com.agentsflex.store.StoreOptions;
import com.agentsflex.store.StoreResult;
import com.alibaba.fastjson.JSONObject;
import com.sun.org.slf4j.internal.Logger;
import com.sun.org.slf4j.internal.LoggerFactory;
import io.milvus.exception.MilvusException;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.response.SearchResp;

import java.util.*;

/**
 * MilvusVectorStore class provides an interface to interact with Milvus Vector Database.
 */
public class MilvusVectorStore extends DocumentStore {

    private static final Logger logger = LoggerFactory.getLogger(MilvusVectorStore.class);
    private final MilvusClientV2 client;
    private final String defaultCollectionName;

    public MilvusVectorStore(MilvusVectorStoreConfig config) {
        ConnectConfig connectConfig = ConnectConfig.builder()
            .uri(config.getHost())
            .token(config.getToken())
            .build();

        this.client = new MilvusClientV2(connectConfig);
        this.defaultCollectionName = config.getDefaultCollectionName();
    }

    @Override
    public StoreResult storeInternal(List<Document> documents, StoreOptions options) {
        // Implement Milvus insert logic
        List<JSONObject> data = new ArrayList<>();
        for (Document doc : documents) {
            JSONObject dict = new JSONObject();
            dict.put("id", doc.getId());
            dict.put("vector", doc.getVector());
            data.add(dict);
        }
        InsertReq insertReq = InsertReq.builder()
            .collectionName(options.getPartitionName(defaultCollectionName))
            .data(data)
            .build();

        client.insert(insertReq);
        return StoreResult.DEFAULT_SUCCESS;
    }

    @Override
    public StoreResult deleteInternal(Collection<Object> ids, StoreOptions options) {
        // Implement Milvus delete logic
        DeleteReq deleteReq = DeleteReq.builder()
            .collectionName(options.getPartitionName(defaultCollectionName))
            .ids(new ArrayList<>(ids))
            .build();

        client.delete(deleteReq);
        return StoreResult.DEFAULT_SUCCESS;

    }

    @Override
    public List<Document> searchInternal(SearchWrapper searchWrapper, StoreOptions options) {
        // Implement Milvus search logic
        SearchReq searchReq = SearchReq.builder()
            .collectionName(options.getCollectionName(defaultCollectionName))
            .annsField("vector")
            .topK(searchWrapper.getMaxResults())
            .filter(searchWrapper.toFilterExpression(MilvusExpressionAdaptor.DEFAULT))
            .data(Collections.singletonList(searchWrapper.getVector()))
            .build();

        try {
            SearchResp resp = client.search(searchReq);
            // Parse and convert search results to Document list
            List<List<SearchResp.SearchResult>> results = resp.getSearchResults();
            List<Document> documents = new ArrayList<>();
            for (List<SearchResp.SearchResult> resultList : results) {
                for (SearchResp.SearchResult result : resultList) {
                    Map<String, Object> entity = result.getEntity();
                    Document doc = new Document();
                    doc.setId(result.getId());
                    Object vectorObj = entity.get("vector");
                    if (vectorObj instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<Double> vectorList = (List<Double>) vectorObj;
                        // 使用 Stream API 将 List<Double> 转换为 double[]
                        double[] vector = vectorList.stream()
                            .mapToDouble(Double::doubleValue)
                            .toArray();
                        doc.setVector(vector);
                    }
                    doc.addMetadata(entity);
                    documents.add(doc);
                }
            }

            return documents;
        } catch (MilvusException e) {
            logger.error("Error searching in Milvus", e);
            return Collections.emptyList();
        }
    }

    @Override
    public StoreResult updateInternal(List<Document> documents, StoreOptions options) {
        if (documents == null || documents.isEmpty()) {
            return StoreResult.DEFAULT_SUCCESS;
        }
        List<JSONObject> data = new ArrayList<>();
        for (Document doc : documents) {
            JSONObject dict = new JSONObject();
            dict.put("id", doc.getId());
            dict.put("vector", doc.getVector());
            // 将其他元数据字段添加到字典中，如果需要的话
            data.add(dict);
        }
        UpsertReq upsertReq = UpsertReq.builder()
            .collectionName(options.getPartitionName(defaultCollectionName))
            .data(data)
            .build();
        client.upsert(upsertReq);
        return StoreResult.DEFAULT_SUCCESS;
    }

}

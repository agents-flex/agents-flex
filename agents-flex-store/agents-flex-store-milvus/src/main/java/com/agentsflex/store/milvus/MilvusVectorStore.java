/*
 *  Copyright (c) 2023-2025, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.store.milvus;

import com.agentsflex.document.Document;
import com.agentsflex.store.DocumentStore;
import com.agentsflex.store.SearchWrapper;
import com.agentsflex.store.StoreOptions;
import com.agentsflex.store.StoreResult;
import com.agentsflex.util.VectorUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import io.milvus.exception.MilvusException;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.exception.MilvusClientException;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.InsertResp;
import io.milvus.v2.service.vector.response.SearchResp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * MilvusVectorStore class provides an interface to interact with Milvus Vector Database.
 */
public class MilvusVectorStore extends DocumentStore {

    private static final Logger logger = LoggerFactory.getLogger(MilvusVectorStore.class);
    private final MilvusClientV2 client;
    private final String defaultCollectionName;
    private final MilvusVectorStoreConfig config;

    public MilvusVectorStore(MilvusVectorStoreConfig config) {
        ConnectConfig connectConfig = ConnectConfig.builder()
            .uri(config.getUri())
            .dbName(config.getDatabaseName())
            .token(config.getToken())
            .username(config.getUsername())
            .password(config.getPassword())
            .build();

        this.client = new MilvusClientV2(connectConfig);
        this.defaultCollectionName = config.getDefaultCollectionName();
        this.config = config;
    }

    @Override
    public StoreResult storeInternal(List<Document> documents, StoreOptions options) {
        List<JSONObject> data = new ArrayList<>();
        for (Document doc : documents) {
            JSONObject dict = new JSONObject();
            dict.put("id", doc.getId());
            dict.put("content", doc.getContent());
            dict.put("vector", VectorUtil.toFloatList(doc.getVector()));

            Map<String, Object> metadatas = doc.getMetadatas();
            JSONObject jsonObject = JSON.parseObject(JSON.toJSONBytes(metadatas == null ? Collections.EMPTY_MAP : metadatas));
            dict.put("metadata", jsonObject);
            data.add(dict);
        }

        String collectionName = options.getCollectionNameOrDefault(defaultCollectionName);
        InsertReq insertReq = InsertReq.builder()
            .collectionName(collectionName)
            .partitionName(options.getPartitionName())
            .data(data)
            .build();
        try {
            InsertResp insertResp = client.insert(insertReq);
        } catch (MilvusClientException e) {
            if (e.getMessage() != null && e.getMessage().contains("collection not found")
                && config.isAutoCreateCollection()
                && options.getMetadata("forInternal") == null) {

                createCollection(collectionName);

                //store
                options.addMetadata("forInternal", true);
                storeInternal(documents, options);
            } else {
                return StoreResult.fail();
            }
        }

        return StoreResult.successWithIds(documents);
    }


    private void createCollection(String collectionName) {
        List<CreateCollectionReq.FieldSchema> fieldSchemaList = new ArrayList<>();

        //id
        CreateCollectionReq.FieldSchema id = CreateCollectionReq.FieldSchema.builder()
            .name("id")
            .dataType(DataType.VarChar)
            .maxLength(36)
            .isPrimaryKey(true)
            .autoID(false)
            .build();
        fieldSchemaList.add(id);

        //content
        CreateCollectionReq.FieldSchema content = CreateCollectionReq.FieldSchema.builder()
            .name("content")
            .dataType(DataType.VarChar)
            .maxLength(65535)
            .build();
        fieldSchemaList.add(content);

        //metadata
        CreateCollectionReq.FieldSchema metadata = CreateCollectionReq.FieldSchema.builder()
            .name("metadata")
            .dataType(DataType.JSON)
            .build();
        fieldSchemaList.add(metadata);

        //vector
        CreateCollectionReq.FieldSchema vector = CreateCollectionReq.FieldSchema.builder()
            .name("vector")
            .dataType(DataType.FloatVector)
            .dimension(this.getEmbeddingModel().dimensions())
            .build();
        fieldSchemaList.add(vector);

        CreateCollectionReq.CollectionSchema collectionSchema = CreateCollectionReq.CollectionSchema
            .builder()
            .fieldSchemaList(fieldSchemaList)
            .build();

        CreateCollectionReq createCollectionReq = CreateCollectionReq.builder()
            .collectionName(collectionName)
            .collectionSchema(collectionSchema)
            .primaryFieldName("id")
            .description("Agents Flex Vector Store")
            .vectorFieldName("vector")
            .build();

        client.createCollection(createCollectionReq);
    }

    @Override
    public StoreResult deleteInternal(Collection<Object> ids, StoreOptions options) {
        // Implement Milvus delete logic
        DeleteReq deleteReq = DeleteReq.builder()
            .collectionName(options.getCollectionNameOrDefault(defaultCollectionName))
            .partitionName(options.getPartitionName())
            .ids(new ArrayList<>(ids))
            .build();

        DeleteResp deleteResp = client.delete(deleteReq);
        return StoreResult.success();

    }

    @Override
    public List<Document> searchInternal(SearchWrapper searchWrapper, StoreOptions options) {
        // Implement Milvus search logic
        SearchReq searchReq = SearchReq.builder()
            .collectionName(options.getCollectionNameOrDefault(defaultCollectionName))
            .annsField("vector")
            .partitionNames(options.getPartitionNamesOrEmpty())
            .topK(searchWrapper.getMaxResults())
            .filter(searchWrapper.toFilterExpression(MilvusExpressionAdaptor.DEFAULT))
            .data(Collections.singletonList(VectorUtil.toFloatList(searchWrapper.getVector())))
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
            return StoreResult.success();
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
            .collectionName(options.getCollectionNameOrDefault(defaultCollectionName))
            .partitionName(options.getPartitionName())
            .data(data)
            .build();
        client.upsert(upsertReq);
        return StoreResult.successWithIds(documents);
    }


    private boolean checkCollectionExists(String collectionName) {
        HasCollectionReq hasCollectionParam = HasCollectionReq.builder()
            .collectionName(collectionName).build();
        Boolean exist = this.client.hasCollection(hasCollectionParam);
        return exist != null && exist;
    }
}

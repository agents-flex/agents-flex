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
package com.agentsflex.store.qcloud;

import com.agentsflex.document.Document;
import com.agentsflex.llm.client.HttpClient;
import com.agentsflex.store.DocumentStore;
import com.agentsflex.store.SearchWrapper;
import com.agentsflex.store.StoreOptions;
import com.agentsflex.store.StoreResult;
import com.agentsflex.util.StringUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * doc https://cloud.tencent.com/document/product/1709/95121
 */
public class QCloudVectorStore extends DocumentStore {

    private QCloudVectorStoreConfig config;

    private final HttpClient httpUtil = new HttpClient();


    public QCloudVectorStore(QCloudVectorStoreConfig config) {
        this.config = config;
    }


    @Override
    public StoreResult storeInternal(List<Document> documents, StoreOptions options) {
        if (documents == null || documents.isEmpty()) {
            return StoreResult.DEFAULT_SUCCESS;
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer account=" + config.getAccount() + "&api_key=" + config.getApiKey());

        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("database", config.getDatabase());
        payloadMap.put("collection", config.getDefaultCollectionName());


        List<Map<String, Object>> payloadDocs = new ArrayList<>();
        for (Document vectorDocument : documents) {
            Map<String, Object> document = new HashMap<>();
            if (vectorDocument.getMetadatas() != null) {
                document.putAll(vectorDocument.getMetadatas());
            }
            document.put("vector", vectorDocument.getVector());
            document.put("id", vectorDocument.getId());
            payloadDocs.add(document);
        }
        payloadMap.put("documents", payloadDocs);

        String payload = JSON.toJSONString(payloadMap);
        httpUtil.post(config.getHost() + "/document/upsert", headers, payload);
        return StoreResult.DEFAULT_SUCCESS;
    }


    @Override
    public StoreResult deleteInternal(Collection<String> ids, StoreOptions options) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer account=" + config.getAccount() + "&api_key=" + config.getApiKey());

        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("database", config.getDatabase());
        payloadMap.put("collection", options.getPartitionName(config.getDefaultCollectionName()));

        Map<String, Object> documentIdsObj = new HashMap<>();
        documentIdsObj.put("documentIds", ids);
        payloadMap.put("query", documentIdsObj);

        String payload = JSON.toJSONString(payloadMap);

        httpUtil.post(config.getHost() + "/document/delete", headers, payload);

        return StoreResult.DEFAULT_SUCCESS;
    }


    @Override
    public StoreResult updateInternal(List<Document> documents, StoreOptions options) {
        if (documents == null || documents.isEmpty()) {
            return StoreResult.DEFAULT_SUCCESS;
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer account=" + config.getAccount() + "&api_key=" + config.getApiKey());

        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("database", config.getDatabase());
        payloadMap.put("collection", options.getPartitionName(config.getDefaultCollectionName()));

        for (Document document : documents) {
            Map<String, Object> documentIdsObj = new HashMap<>();
            documentIdsObj.put("documentIds", Collections.singletonList(document.getId()));
            payloadMap.put("query", documentIdsObj);
            payloadMap.put("update", document.getMetadatas());
            String payload = JSON.toJSONString(payloadMap);
            httpUtil.post(config.getHost() + "/document/update", headers, payload);
        }

        return StoreResult.DEFAULT_SUCCESS;
    }

    @Override
    public List<Document> searchInternal(SearchWrapper searchWrapper, StoreOptions options) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer account=" + config.getAccount() + "&api_key=" + config.getApiKey());
        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("database", config.getDatabase());
        payloadMap.put("collection", options.getCollectionName(config.getDefaultCollectionName()));

        Map<String, Object> searchMap = new HashMap<>();
        searchMap.put("vectors", Collections.singletonList(searchWrapper.getVector()));

        if (searchWrapper.getMaxResults() != null) {
            searchMap.put("limit", searchWrapper.getMaxResults());
        }

        payloadMap.put("search", searchMap);


        String payload = JSON.toJSONString(payloadMap);

        // https://cloud.tencent.com/document/product/1709/95123
        String response = httpUtil.post(config.getHost() + "/document/search", headers, payload);
        if (StringUtil.noText(response)) {
            return null;
        }

        List<Document> result = new ArrayList<>();
        JSONObject rootObject = JSON.parseObject(response);
        int code = rootObject.getIntValue("code");
        if (code != 0) {
            LoggerFactory.getLogger(QCloudVectorStore.class).error("can not search in QCloudVectorStore, code:" + code + ",  message: " + rootObject.getString("msg"));
            return null;
        }

        JSONArray rootDocs = rootObject.getJSONArray("documents");
        for (int i = 0; i < rootDocs.size(); i++) {
            JSONArray docs = rootDocs.getJSONArray(i);
            for (int j = 0; j < docs.size(); j++) {
                JSONObject doc = docs.getJSONObject(j);
                Document vd = new Document();
                vd.setId(doc.getString("id"));
                doc.remove("id");
                vd.addMetadata(doc);
                result.add(vd);
            }
        }
        return result;
    }
}

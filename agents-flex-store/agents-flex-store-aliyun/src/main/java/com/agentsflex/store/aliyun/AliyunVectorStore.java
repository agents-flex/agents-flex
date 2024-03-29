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
package com.agentsflex.store.aliyun;

import com.agentsflex.llm.client.HttpClient;
import com.agentsflex.util.StringUtil;
import com.agentsflex.store.SearchWrapper;
import com.agentsflex.store.VectorDocument;
import com.agentsflex.store.VectorStore;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.*;

/**
 * 文档 https://help.aliyun.com/document_detail/2510317.html
 */
public class AliyunVectorStore extends VectorStore<VectorDocument> {

    private AliyunVectorStoreConfig config;

    private final HttpClient httpUtil = new HttpClient();

    public AliyunVectorStore(AliyunVectorStoreConfig config) {
        this.config = config;
    }

    @Override
    public void store(List<VectorDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("dashvector-auth-token", config.getApiKey());

        Map<String, Object> payloadMap = new HashMap<>();

        List<Map<String, Object>> payloadDocs = new ArrayList<>();
        for (VectorDocument vectorDocument : documents) {
            Map<String, Object> document = new HashMap<>();
            if (vectorDocument.getMetadatas() != null) {
                document.put("fields", vectorDocument.getMetadatas());
            }
            document.put("vector", vectorDocument.getVector());
            document.put("id", vectorDocument.getId());
            payloadDocs.add(document);
        }

        payloadMap.put("docs", payloadDocs);

        String payload = JSON.toJSONString(payloadMap);
        String collectionName = StringUtil.obtainFirstHasText(documents.get(0).getCollectionName(), config.getDefaultCollectionName());
        httpUtil.post("https://" + config.getEndpoint() + "/v1/collections/" + collectionName + "/docs", headers, payload);
    }


    @Override
    public void delete(Collection<String> ids, String collectionName, String partitionName) {

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("dashvector-auth-token", config.getApiKey());

        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("ids", ids);
        String payload = JSON.toJSONString(payloadMap);
        collectionName = StringUtil.obtainFirstHasText(collectionName, config.getDefaultCollectionName());
        httpUtil.delete("https://" + config.getEndpoint() + "/v1/collections/" + collectionName + "/docs", headers, payload);
    }


    @Override
    public void update(List<VectorDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("dashvector-auth-token", config.getApiKey());

        Map<String, Object> payloadMap = new HashMap<>();

        List<Map<String, Object>> payloadDocs = new ArrayList<>();
        for (VectorDocument vectorDocument : documents) {
            Map<String, Object> document = new HashMap<>();
            if (vectorDocument.getMetadatas() != null) {
                document.put("fields", vectorDocument.getMetadatas());
            }
            document.put("vector", vectorDocument.getVector());
            document.put("id", vectorDocument.getId());
            payloadDocs.add(document);
        }

        payloadMap.put("docs", payloadDocs);

        String payload = JSON.toJSONString(payloadMap);
        String collectionName = StringUtil.obtainFirstHasText(documents.get(0).getCollectionName(), config.getDefaultCollectionName());
        httpUtil.put("https://" + config.getEndpoint() + "/v1/collections/" + collectionName + "/docs", headers, payload);
    }


    @Override
    public List<VectorDocument> search(SearchWrapper wrapper) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("dashvector-auth-token", config.getApiKey());

        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("vector", wrapper.getVector());
        payloadMap.put("topk", wrapper.getMaxResults());
        payloadMap.put("include_vector", wrapper.getWithVector());
        payloadMap.put("filter", wrapper.toFilterExpression());

        String payload = JSON.toJSONString(payloadMap);
        String collectionName = StringUtil.obtainFirstHasText(wrapper.getCollectionName(), config.getDefaultCollectionName());
        String result = httpUtil.post("https://" + config.getEndpoint() + "/v1/collections/" + collectionName + "/query", headers, payload);
        if (StringUtil.noText(result)) {
            return null;
        }

        //https://help.aliyun.com/document_detail/2510319.html
        JSONObject rootObject = JSON.parseObject(result);
        JSONArray output = rootObject.getJSONArray("output");

        List<VectorDocument> documents = new ArrayList<>(output.size());
        for (int i = 0; i < output.size(); i++) {
            JSONObject jsonObject = output.getJSONObject(i);
            VectorDocument document = new VectorDocument();
            document.setId(jsonObject.getString("id"));
            document.setVector(jsonObject.getObject("vector", double[].class));

            JSONObject fields = jsonObject.getJSONObject("fields");
            document.addMetadata(fields);

            documents.add(document);
        }

        return documents;
    }
}

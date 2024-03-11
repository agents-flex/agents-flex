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
import com.agentsflex.store.RetrieveWrapper;
import com.agentsflex.store.VectorDocument;
import com.agentsflex.store.VectorStore;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.*;

/**
 * https://help.aliyun.com/document_detail/2510317.html
 */
public class AliyunVectorStore extends VectorStore<VectorDocument> {

    private AliyunVectorStoreConfig config;

    private final HttpClient httpUtil = new HttpClient();

    public AliyunVectorStore(AliyunVectorStoreConfig config) {
        this.config = config;
    }

    @Override
    public void store(List<VectorDocument> documents) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("dashvector-auth-token", config.getApiKey());

        Map<String, Object> payloadMap = new HashMap<>();

        List<Map<String,Object>> payloadDocs = new ArrayList<>();
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
        httpUtil.post("https://" + config.getEndpoint() + "/v1/collections/" + config.getCollection() + "/docs", headers, payload);
    }


    @Override
    public void delete(Collection<String> ids) {

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("dashvector-auth-token", config.getApiKey());

        Map<String,Object> payloadMap = new HashMap<>();
        payloadMap.put("ids",ids);
        String payload = JSON.toJSONString(payloadMap);

        httpUtil.delete("https://" + config.getEndpoint() + "/v1/collections/" + config.getCollection() + "/docs", headers, payload);
    }


    @Override
    public void update(List<VectorDocument> documents) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("dashvector-auth-token", config.getApiKey());

        Map<String, Object> payloadMap = new HashMap<>();

        List<Map<String,Object>> payloadDocs = new ArrayList<>();
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
        httpUtil.put("https://" + config.getEndpoint() + "/v1/collections/" + config.getCollection() + "/docs", headers, payload);
    }


    @Override
    public List<VectorDocument> retrieval(RetrieveWrapper wrapper) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("dashvector-auth-token", config.getApiKey());

        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("vector", wrapper.getVector());
        payloadMap.put("topk", wrapper.getLimit());
        payloadMap.put("include_vector", wrapper.isWithVector());

        String payload = JSON.toJSONString(payloadMap);
        String result = httpUtil.post("https://" + config.getEndpoint() + "/v1/collections/" + config.getCollection() + "/query", headers, payload);
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

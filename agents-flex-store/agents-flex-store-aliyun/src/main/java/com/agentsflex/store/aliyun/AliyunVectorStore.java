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
package com.agentsflex.store.aliyun;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.model.client.HttpClient;
import com.agentsflex.core.store.DocumentStore;
import com.agentsflex.core.store.SearchWrapper;
import com.agentsflex.core.store.StoreOptions;
import com.agentsflex.core.store.StoreResult;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 文档 https://help.aliyun.com/document_detail/2510317.html
 */
public class AliyunVectorStore extends DocumentStore {
    private static final Logger LOG = LoggerFactory.getLogger(AliyunVectorStore.class);

    private AliyunVectorStoreConfig config;

    private final HttpClient httpUtil = new HttpClient();

    public AliyunVectorStore(AliyunVectorStoreConfig config) {
        this.config = config;
    }

    @Override
    public StoreResult doStore(List<Document> documents, StoreOptions options) {
        if (documents == null || documents.isEmpty()) {
            return StoreResult.success();
        }
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("dashvector-auth-token", config.getApiKey());

        Map<String, Object> payloadMap = new HashMap<>();

        List<Map<String, Object>> payloadDocs = new ArrayList<>();
        for (Document vectorDocument : documents) {
            Map<String, Object> document = new HashMap<>();
            if (vectorDocument.getMetadataMap() != null) {
                document.put("fields", vectorDocument.getMetadataMap());
            }
            document.put("vector", vectorDocument.getVector());
            document.put("id", vectorDocument.getId());
            payloadDocs.add(document);
        }

        payloadMap.put("docs", payloadDocs);

        String payload = JSON.toJSONString(payloadMap);
        String url = "https://" + config.getEndpoint() + "/v1/collections/"
            + options.getCollectionNameOrDefault(config.getDefaultCollectionName()) + "/docs";
        String response = httpUtil.post(url, headers, payload);

        if (StringUtil.noText(response)) {
            return StoreResult.fail();
        }

        JSONObject jsonObject = JSON.parseObject(response);
        Integer code = jsonObject.getInteger("code");
        if (code != null && code == 0) {
            return StoreResult.successWithIds(documents);
        } else {
            LOG.error("delete vector fail: " + response);
            return StoreResult.fail();
        }
    }


    @Override
    public StoreResult doDelete(Collection<?> ids, StoreOptions options) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("dashvector-auth-token", config.getApiKey());

        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("ids", ids);
        String payload = JSON.toJSONString(payloadMap);

        String url = "https://" + config.getEndpoint() + "/v1/collections/"
            + options.getCollectionNameOrDefault(config.getDefaultCollectionName()) + "/docs";
        String response = httpUtil.delete(url, headers, payload);
        if (StringUtil.noText(response)) {
            return StoreResult.fail();
        }

        JSONObject jsonObject = JSON.parseObject(response);
        Integer code = jsonObject.getInteger("code");
        if (code != null && code == 0) {
            return StoreResult.success();
        } else {
            LOG.error("delete vector fail: " + response);
            return StoreResult.fail();
        }
    }


    @Override
    public StoreResult doUpdate(List<Document> documents, StoreOptions options) {
        if (documents == null || documents.isEmpty()) {
            return StoreResult.success();
        }
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("dashvector-auth-token", config.getApiKey());

        Map<String, Object> payloadMap = new HashMap<>();

        List<Map<String, Object>> payloadDocs = new ArrayList<>();
        for (Document vectorDocument : documents) {
            Map<String, Object> document = new HashMap<>();
            if (vectorDocument.getMetadataMap() != null) {
                document.put("fields", vectorDocument.getMetadataMap());
            }
            document.put("vector", vectorDocument.getVector());
            document.put("id", vectorDocument.getId());
            payloadDocs.add(document);
        }

        payloadMap.put("docs", payloadDocs);

        String payload = JSON.toJSONString(payloadMap);
        String url = "https://" + config.getEndpoint() + "/v1/collections/"
            + options.getCollectionNameOrDefault(config.getDefaultCollectionName()) + "/docs";
        String response = httpUtil.put(url, headers, payload);

        if (StringUtil.noText(response)) {
            return StoreResult.fail();
        }

        JSONObject jsonObject = JSON.parseObject(response);
        Integer code = jsonObject.getInteger("code");
        if (code != null && code == 0) {
            return StoreResult.successWithIds(documents);
        } else {
            LOG.error("delete vector fail: " + response);
            return StoreResult.fail();
        }

    }


    @Override
    public List<Document> doSearch(SearchWrapper wrapper, StoreOptions options) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("dashvector-auth-token", config.getApiKey());

        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("vector", wrapper.getVector());
        payloadMap.put("topk", wrapper.getMaxResults());
        payloadMap.put("include_vector", wrapper.isWithVector());
        payloadMap.put("filter", wrapper.toFilterExpression());

        String payload = JSON.toJSONString(payloadMap);
        String url = "https://" + config.getEndpoint() + "/v1/collections/"
            + options.getCollectionNameOrDefault(config.getDefaultCollectionName()) + "/query";
        String result = httpUtil.post(url, headers, payload);

        if (StringUtil.noText(result)) {
            return null;
        }

        //https://help.aliyun.com/document_detail/2510319.html
        JSONObject rootObject = JSON.parseObject(result);
        int code = rootObject.getIntValue("code");
        if (code != 0) {
            //error
            LoggerFactory.getLogger(AliyunVectorStore.class).error("can not search data AliyunVectorStore（code: " + code + "), message: " + rootObject.getString("message"));
            return null;
        }

        JSONArray output = rootObject.getJSONArray("output");
        List<Document> documents = new ArrayList<>(output.size());
        for (int i = 0; i < output.size(); i++) {
            JSONObject jsonObject = output.getJSONObject(i);
            Document document = new Document();
            document.setId(jsonObject.getString("id"));
            document.setVector(jsonObject.getObject("vector", float[].class));
            // 阿里云数据采用余弦相似度计算 jsonObject.getDoubleValue("score") 表示余弦距离，
            // 原始余弦距离范围是[0, 2]，0表示最相似，2表示最不相似
            Double distance = jsonObject.getDouble("score");
            if (distance != null) {
                double score = distance / 2.0;
                document.setScore(1.0d - score);
            }


            JSONObject fields = jsonObject.getJSONObject("fields");
            document.addMetadata(fields);

            documents.add(document);
        }

        return documents;
    }
}

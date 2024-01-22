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
package com.agentsflex.vector.qcloud;

import com.agentsflex.util.OKHttpUtil;
import com.agentsflex.util.StringUtil;
import com.agentsflex.vector.RetrieveWrapper;
import com.agentsflex.vector.VectorDocument;
import com.agentsflex.vector.VectorStorage;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.*;

/**
 * doc https://cloud.tencent.com/document/product/1709/95121
 */
public class QCloudVectorStorage extends VectorStorage<VectorDocument> {

    private QCloudVectorStorageConfig config;

    private final OKHttpUtil httpUtil = new OKHttpUtil();


    public QCloudVectorStorage(QCloudVectorStorageConfig config) {
        this.config = config;
    }


    @Override
    public void store(List<VectorDocument> documents) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer account=" + config.getAccount() + "&api_key=" + config.getApiKey());

        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("database", config.getDatabase());
        payloadMap.put("collection", config.getCollection());


        List<Map<String, Object>> payloadDocs = new ArrayList<>();
        for (VectorDocument vectorDocument : documents) {
            Map<String, Object> document = new HashMap<>();
            if (vectorDocument.getMetadataMap() != null) {
                document.putAll(vectorDocument.getMetadataMap());
            }
            document.put("vector", vectorDocument.getVector());
            document.put("id", vectorDocument.getId());
            payloadDocs.add(document);
        }
        payloadMap.put("documents", payloadDocs);

        String payload = JSON.toJSONString(payloadMap);
        httpUtil.post(config.getHost() + "/document/upsert", headers, payload);
    }


    @Override
    public void delete(Collection<String> ids) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer account=" + config.getAccount() + "&api_key=" + config.getApiKey());

        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("database", config.getDatabase());
        payloadMap.put("collection", config.getCollection());

        Map<String, Object> documentIdsObj = new HashMap<>();
        documentIdsObj.put("documentIds", ids);
        payloadMap.put("query", documentIdsObj);

        String payload = JSON.toJSONString(payloadMap);

        httpUtil.post(config.getHost() + "/document/delete", headers, payload);
    }


    @Override
    public void update(List<VectorDocument> documents) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer account=" + config.getAccount() + "&api_key=" + config.getApiKey());

        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("database", config.getDatabase());
        payloadMap.put("collection", config.getCollection());

        for (VectorDocument document : documents) {
            Map<String, Object> documentIdsObj = new HashMap<>();
            documentIdsObj.put("documentIds", Collections.singletonList(document.getId()));
            payloadMap.put("query", documentIdsObj);
            payloadMap.put("update", document.getMetadataMap());
            String payload = JSON.toJSONString(payloadMap);
            httpUtil.post(config.getHost() + "/document/update", headers, payload);
        }
    }

    @Override
    public List<VectorDocument> retrieval(RetrieveWrapper retrieveWrapper) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer account=" + config.getAccount() + "&api_key=" + config.getApiKey());
        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("database", config.getDatabase());
        payloadMap.put("collection", config.getCollection());

        Map<String, Object> searchMap = new HashMap<>();
        searchMap.put("vector", retrieveWrapper.getVector());

        if (retrieveWrapper.getLimit() != null) {
            searchMap.put("limit", retrieveWrapper.getLimit());
        }

        payloadMap.put("search", searchMap);


        String payload = JSON.toJSONString(payloadMap);

        //https://cloud.tencent.com/document/product/1709/95123
        String response = httpUtil.post(config.getHost() + "/document/search", headers, payload);
        if (StringUtil.noText(response)) {
            return null;
        }

        List<VectorDocument> result = new ArrayList<>();
        JSONObject rootObject = JSON.parseObject(response);
        JSONArray rootDocs = rootObject.getJSONArray("documents");
        for (int i = 0; i < rootDocs.size(); i++) {
            JSONArray docs = rootDocs.getJSONArray(i);
            for (int j = 0; j < docs.size(); j++) {
                JSONObject doc = docs.getJSONObject(j);
                VectorDocument vd = new VectorDocument();
                vd.setId(doc.getString("id"));
                doc.remove("id");
                vd.addMetadata(doc);
                result.add(vd);
            }
        }
        return result;
    }
}

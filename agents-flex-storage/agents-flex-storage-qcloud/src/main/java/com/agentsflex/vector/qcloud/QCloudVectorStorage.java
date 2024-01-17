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
import com.alibaba.fastjson.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class QCloudVectorStorage extends VectorStorage<VectorDocument> {

    private QCloudVectorStorageConfig config;

    public QCloudVectorStorage(QCloudVectorStorageConfig config) {
        this.config = config;
    }

    @Override
    public void store(VectorDocument vectorDocument) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer account=" + config.getAccount() + "&api_key=" + config.getApiKey());

        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("database", config.getDatabase());
        payloadMap.put("collection", config.getCollection());

        Map<String, Object> document = new HashMap<>();
        if (vectorDocument.getMetadataMap() != null) {
            document.putAll(vectorDocument.getMetadataMap());
        }
        document.put("vector", vectorDocument.getVector());
        document.put("id", vectorDocument.getId());
        payloadMap.put("documents", Collections.singletonList(document));

        String payload = JSON.toJSONString(payloadMap);
        OKHttpUtil.post(config.getHost() + "/document/upsert", headers, payload);
    }

    @Override
    public void delete(VectorDocument document) {

    }

    @Override
    public void update(VectorDocument document) {

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
        String response = OKHttpUtil.post(config.getHost() + "/document/search", headers, payload);
        if (StringUtil.noText(response)) {
            return null;
        }

        JSONObject rootObject = JSON.parseObject(response);

        return null;
    }
}

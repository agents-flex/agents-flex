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
package com.agentsflex.rerank;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.llm.client.HttpClient;
import com.agentsflex.core.llm.rerank.BaseRerankModel;
import com.agentsflex.core.llm.rerank.RerankException;
import com.agentsflex.core.llm.rerank.RerankOptions;
import com.agentsflex.core.util.LogUtil;
import com.agentsflex.core.util.Maps;
import com.agentsflex.core.util.StringUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONPath;

import java.util.*;

public class DefaultRerankModel extends BaseRerankModel<DefaultRerankModelConfig> {

    private HttpClient httpClient = new HttpClient();

    public DefaultRerankModel(DefaultRerankModelConfig config) {
        super(config);
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }

    public void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public List<Document> rerank(String query, List<Document> documents, RerankOptions options) {

        DefaultRerankModelConfig config = getConfig();
        String url = config.getEndpoint() + config.getBasePath();

        Map<String, String> headers = new HashMap<>(2);
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer " + config.getApiKey());

        List<String> payloadDocuments = new ArrayList<>(documents.size());
        for (Document document : documents) {
            payloadDocuments.add(document.getContent());
        }

        String payload = Maps.of("model", options.getModelOrDefault(config.getModel()))
            .set("query", query)
            .set("documents", payloadDocuments)
            .toJSON();

        if (config.isDebug()) {
            LogUtil.println(">>>>send payload:" + payload);
        }

        String response = httpClient.post(url, headers, payload);
        if (config.isDebug()) {
            LogUtil.println(">>>>receive payload:" + response);
        }

        if (StringUtil.noText(response)) {
            throw new RerankException("empty response");
        }

        //{
        //  "model": "Qwen3-Reranker-4B",
        //  "usage": {
        //    "totalTokens": 0,
        //    "promptTokens": 0
        //  },
        //  "results": [
        //    {
        //      "index": 0,
        //      "document": {
        //        "text": "Use pandas: `import pandas as pd; df = pd.read_csv('data.csv')`"
        //      },
        //      "relevance_score": 0.95654296875
        //    },
        //    {
        //      "index": 3,
        //      "document": {
        //        "text": "CSV means Comma Separated Values. Python files can be opened using read() method."
        //      },
        //      "relevance_score": 0.822265625
        //    },
        //    {
        //      "index": 1,
        //      "document": {
        //        "text": "You can read CSV files with numpy.loadtxt()"
        //      },
        //      "relevance_score": 0.310791015625
        //    },
        //    {
        //      "index": 2,
        //      "document": {
        //        "text": "To write JSON files, use json.dump() in Python"
        //      },
        //      "relevance_score": 0.00009608268737792969
        //    }
        //  ]
        //}
        JSONObject jsonObject = JSON.parseObject(response);
        JSONArray results = (JSONArray) JSONPath.eval(jsonObject, config.getResultsJsonPath());

        if (results == null || results.isEmpty()) {
            throw new RerankException("empty results");
        }


        for (int i = 0; i < results.size(); i++) {
            JSONObject result = results.getJSONObject(i);
            int index = result.getIntValue(config.getIndexJsonKey());
            Document document = documents.get(index);
            document.setScore(result.getDoubleValue(config.getScoreJsonKey()));
        }

        // 对 documents 排序， score 越大的越靠前
        documents.sort(Comparator.comparingDouble(Document::getScore).reversed());

        return documents;
    }
}

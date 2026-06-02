/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
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
package com.agentsflex.websearch.bocha;

import com.agentsflex.core.util.StringUtil;
import com.agentsflex.websearch.SearchProvider;
import com.agentsflex.websearch.SearchRequest;
import com.agentsflex.websearch.SearchResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class BochaSearchProvider implements SearchProvider {

    private static final String BASE_URL = "https://api.bocha.cn/v1/web-search";

    private final String apiKey;
    private final OkHttpClient httpClient;
    private Boolean summary;

    public BochaSearchProvider(String apiKey) {
        this(apiKey, new OkHttpClient());
    }


    public BochaSearchProvider(String apiKey, OkHttpClient httpClient) {
        if (StringUtil.noText(apiKey)) {
            throw new IllegalArgumentException("apiKey must not be empty");
        }

        if (httpClient == null) {
            throw new IllegalArgumentException("OkHttpClient must not be null");
        }

        this.apiKey = apiKey;
        this.httpClient = httpClient;
    }

    public String getApiKey() {
        return apiKey;
    }

    public OkHttpClient getHttpClient() {
        return httpClient;
    }

    public Boolean getSummary() {
        return summary;
    }

    public void setSummary(Boolean summary) {
        this.summary = summary;
    }

    @Override
    public List<SearchResult> search(SearchRequest request) {

        if (request == null || StringUtil.noText(request.getQuery())) {
            return Collections.emptyList();
        }

        try {
            String body = execute(request, request.getMaxResults());

            if (StringUtil.noText(body)) {
                return Collections.emptyList();
            }

            JSONObject root = JSON.parseObject(body);
            JSONObject data = root.getJSONObject("data");
            if (data == null) {
                return Collections.emptyList();
            }

            JSONObject webPages = data.getJSONObject("webPages");
            if (webPages == null) {
                return Collections.emptyList();
            }

            JSONArray arr = webPages.getJSONArray("value");

            return parse(arr);

        } catch (Exception e) {
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    // ---------------------------------------------------
    // HTTP
    // ---------------------------------------------------

    private String execute(SearchRequest request, int resultCount) throws IOException {

        JSONObject bodyJson = new JSONObject();
        bodyJson.put("query", request.getQuery());
        bodyJson.put("count", resultCount);

        // optional
        bodyJson.put("summary", summary != null ? summary : false);
        bodyJson.put("freshness", "noLimit");


        if (request.getAllowedDomains() != null && !request.getAllowedDomains().isEmpty()) {
            bodyJson.put("include", String.join("|", request.getAllowedDomains()));
        }

        if (request.getBlockedDomains() != null && !request.getBlockedDomains().isEmpty()) {
            bodyJson.put("exclude", String.join("|", request.getBlockedDomains()));
        }

        RequestBody body = RequestBody.create(
            bodyJson.toJSONString(),
            MediaType.parse("application/json")
        );

        Request httpRequest = new Request.Builder()
            .url(BASE_URL)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer " + apiKey)
            .post(body)
            .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {

            if (!response.isSuccessful()) {
                return null;
            }

            ResponseBody rb = response.body();
            return rb != null ? rb.string() : null;
        }
    }

    private List<SearchResult> parse(JSONArray array) {

        if (array == null || array.isEmpty()) {
            return Collections.emptyList();
        }

        List<SearchResult> list = new ArrayList<>();

        for (int i = 0; i < array.size(); i++) {

            JSONObject item = array.getJSONObject(i);
            if (item == null) continue;

            String title = item.getString("name");
            String url = item.getString("url");
            String snippet = item.getString("snippet");
            String summary = item.getString("summary");

            if (StringUtil.noText(title) || StringUtil.noText(url)) {
                continue;
            }

            SearchResult r = new SearchResult();
            r.setTitle(title);
            r.setUrl(url);

            // Bocha 优先 summary，其次 snippet
            if (StringUtil.hasText(summary)) {
                r.setDescription(summary);
            } else {
                r.setDescription(snippet);
            }

            list.add(r);
        }

        return list;
    }


}

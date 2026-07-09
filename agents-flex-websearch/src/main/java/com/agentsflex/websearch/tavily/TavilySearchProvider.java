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
package com.agentsflex.websearch.tavily;

import com.agentsflex.core.model.client.OkHttpClientUtil;
import com.agentsflex.core.util.StringUtil;
import com.agentsflex.websearch.SearchException;
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

public class TavilySearchProvider implements SearchProvider {

    private static final String BASE_URL = "https://api.tavily.com/search";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");

    private final String apiKey;
    private final OkHttpClient httpClient;

    public TavilySearchProvider(String apiKey) {
        this(apiKey, OkHttpClientUtil.buildDefaultClient());
    }

    public TavilySearchProvider(String apiKey, OkHttpClient httpClient) {

        if (StringUtil.noText(apiKey)) {
            throw new IllegalArgumentException("apiKey must not be empty");
        }

        if (httpClient == null) {
            throw new IllegalArgumentException("OkHttpClient must not be null");
        }

        this.apiKey = apiKey;
        this.httpClient = httpClient;
    }

    @Override
    public List<SearchResult> search(SearchRequest request) {

        if (request == null || StringUtil.noText(request.getQuery())) {
            throw new RuntimeException("query keyword is null or blank.");
        }

        try {
            String body = execute(request);

            if (StringUtil.noText(body)) {
                return Collections.emptyList();
            }

            JSONObject root = JSON.parseObject(body);
            JSONArray arr = root.getJSONArray("results");

            return parse(arr);
        } catch (Exception e) {
            throw new SearchException(e);
        }
    }

    private String execute(SearchRequest request) throws IOException {

        JSONObject payload = new JSONObject();
        payload.put("api_key", apiKey);
        payload.put("query", request.getQuery());
        payload.put("max_results", request.getMaxResults());

        List<String> allowedDomains = request.getAllowedDomains();
        if (allowedDomains != null && !allowedDomains.isEmpty()) {
            payload.put("include_domains", allowedDomains);
        }

        List<String> blockedDomains = request.getBlockedDomains();
        if (blockedDomains != null && !blockedDomains.isEmpty()) {
            payload.put("exclude_domains", blockedDomains);
        }

        RequestBody requestBody = RequestBody.create(payload.toJSONString(), JSON_MEDIA_TYPE);

        Request httpRequest = new Request.Builder()
            .url(BASE_URL)
            .addHeader("Accept", "application/json")
            .post(requestBody)
            .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {

            if (!response.isSuccessful()) {
                return "";
            }

            ResponseBody body = response.body();
            return body != null ? body.string() : "";
        }
    }

    private List<SearchResult> parse(JSONArray array) {

        if (array == null || array.isEmpty()) {
            return Collections.emptyList();
        }

        List<SearchResult> results = new ArrayList<>();

        for (int i = 0; i < array.size(); i++) {

            JSONObject item = array.getJSONObject(i);
            if (item == null) continue;

            String title = item.getString("title");
            String url = item.getString("url");
            String content = item.getString("content");

            if (!StringUtil.hasText(title) || !StringUtil.hasText(url)) {
                continue;
            }

            SearchResult r = new SearchResult();
            r.setTitle(title);
            r.setUrl(url);
            r.setDescription(content);

            results.add(r);
        }

        return results;
    }

}

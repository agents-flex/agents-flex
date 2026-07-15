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
package com.agentsflex.websearch.firecrawl;

import com.agentsflex.core.model.client.OkHttpClientUtil;
import com.agentsflex.core.util.StringUtil;
import com.agentsflex.websearch.SearchException;
import com.agentsflex.websearch.SearchProvider;
import com.agentsflex.websearch.SearchRequest;
import com.agentsflex.websearch.SearchResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Web search provider backed by the Firecrawl Search API.
 *
 * @see <a href="https://docs.firecrawl.dev/features/search">Firecrawl Search documentation</a>
 */
public class FirecrawlSearchProvider implements SearchProvider {

    private static final String BASE_URL = "https://api.firecrawl.dev/v2/search";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");

    private final String apiKey;
    private final OkHttpClient httpClient;

    public FirecrawlSearchProvider() {
        this(null, OkHttpClientUtil.buildDefaultClient());
    }

    public FirecrawlSearchProvider(String apiKey) {
        this(apiKey, OkHttpClientUtil.buildDefaultClient());
    }

    public FirecrawlSearchProvider(OkHttpClient httpClient) {
        this(null, httpClient);
    }

    public FirecrawlSearchProvider(String apiKey, OkHttpClient httpClient) {
        if (httpClient == null) {
            throw new IllegalArgumentException("OkHttpClient must not be null");
        }

        this.apiKey = apiKey;
        this.httpClient = httpClient;
    }

    @Override
    public List<SearchResult> search(SearchRequest request) {
        if (request == null || StringUtil.noText(request.getQuery())) {
            throw new IllegalArgumentException("query keyword is null or blank.");
        }

        try {
            String body = execute(request);
            if (StringUtil.noText(body)) {
                return Collections.emptyList();
            }

            JSONObject root = JSON.parseObject(body);
            if (Boolean.FALSE.equals(root.getBoolean("success"))) {
                throw new SearchException("Firecrawl Search Error: " + root.getString("error"));
            }

            JSONObject data = root.getJSONObject("data");
            return data == null ? Collections.emptyList() : parse(data.getJSONArray("web"));
        } catch (SearchException e) {
            throw e;
        } catch (Exception e) {
            throw new SearchException("Failed to search with Firecrawl", e);
        }
    }

    private String execute(SearchRequest request) throws IOException {
        JSONObject payload = new JSONObject();
        payload.put("query", request.getQuery());
        if (request.getMaxResults() != null) {
            payload.put("limit", request.getMaxResults());
        }

        List<String> allowedDomains = request.getAllowedDomains();
        if (allowedDomains != null && !allowedDomains.isEmpty()) {
            payload.put("includeDomains", allowedDomains);
        }

        List<String> blockedDomains = request.getBlockedDomains();
        if (blockedDomains != null && !blockedDomains.isEmpty()) {
            payload.put("excludeDomains", blockedDomains);
        }

        RequestBody requestBody = RequestBody.create(payload.toJSONString(), JSON_MEDIA_TYPE);
        Request.Builder requestBuilder = new Request.Builder()
            .url(BASE_URL)
            .addHeader("Accept", "application/json")
            .post(requestBody);

        if (StringUtil.hasText(apiKey)) {
            requestBuilder.addHeader("Authorization", "Bearer " + apiKey);
        }

        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            ResponseBody responseBody = response.body();
            String body = responseBody != null ? responseBody.string() : "";
            if (!response.isSuccessful()) {
                throw new SearchException("Firecrawl Search HTTP Error: " + response.code()
                    + (StringUtil.hasText(body) ? ", body=" + body : ""));
            }
            return body;
        }
    }

    private List<SearchResult> parse(JSONArray array) {
        if (array == null || array.isEmpty()) {
            return Collections.emptyList();
        }

        List<SearchResult> results = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JSONObject item = array.getJSONObject(i);
            if (item == null) {
                continue;
            }

            String title = item.getString("title");
            String url = item.getString("url");
            if (StringUtil.noText(title) || StringUtil.noText(url)) {
                continue;
            }

            SearchResult result = new SearchResult();
            result.setTitle(title);
            result.setUrl(url);
            result.setDescription(item.getString("description"));
            results.add(result);
        }
        return results;
    }
}

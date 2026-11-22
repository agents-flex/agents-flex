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
package com.agentsflex.websearch.context;

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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ContextSearchProvider implements SearchProvider {
    private static final String URL = "https://api.context.dev/v1/web/search";
    private static final MediaType JSON_TYPE = MediaType.parse("application/json");
    private final String apiKey;
    private final OkHttpClient httpClient;

    public ContextSearchProvider(String apiKey) {
        this(apiKey, OkHttpClientUtil.buildDefaultClient());
    }

    public ContextSearchProvider(String apiKey, OkHttpClient httpClient) {
        if (StringUtil.noText(apiKey)) throw new IllegalArgumentException("apiKey must not be empty");
        for (int i = 0; i < apiKey.length(); i++) {
            if (apiKey.charAt(i) <= 32 || apiKey.charAt(i) >= 127) {
                throw new IllegalArgumentException("apiKey contains invalid characters");
            }
        }
        if (httpClient == null) throw new IllegalArgumentException("OkHttpClient must not be null");
        this.apiKey = apiKey;
        OkHttpClient.Builder builder = httpClient.newBuilder()
            .followRedirects(false).followSslRedirects(false);
        if (httpClient.callTimeoutMillis() == 0) builder.callTimeout(30, TimeUnit.SECONDS);
        this.httpClient = builder.build();
    }

    @Override
    public List<SearchResult> search(SearchRequest request) {
        if (request == null || StringUtil.noText(request.getQuery()) || request.getQuery().length() > 500) {
            throw new IllegalArgumentException("query must contain between 1 and 500 characters");
        }
        int count = request.getMaxResults() == null ? 10 : request.getMaxResults();
        if (count < 1 || count > 100) throw new IllegalArgumentException("maxResults must be between 1 and 100");

        String failure = "Context search failed";
        try {
            JSONObject payload = new JSONObject();
            payload.put("query", request.getQuery());
            payload.put("numResults", count);
            payload.put("highlightsOptions", JSONObject.of("enabled", true));
            if (request.getAllowedDomains() != null && !request.getAllowedDomains().isEmpty()) {
                payload.put("includeDomains", request.getAllowedDomains());
            }
            if (request.getBlockedDomains() != null && !request.getBlockedDomains().isEmpty()) {
                payload.put("excludeDomains", request.getBlockedDomains());
            }
            Request httpRequest = new Request.Builder().url(URL)
                .header("Authorization", "Bearer " + apiKey).header("Accept", "application/json")
                .post(RequestBody.create(payload.toJSONString(), JSON_TYPE)).build();
            try (Response response = httpClient.newCall(httpRequest).execute()) {
                if (!response.isSuccessful()) {
                    failure = "Context Search HTTP Error: " + response.code();
                    throw new SearchException(failure);
                }
                ResponseBody body = response.body();
                JSONObject root = body == null ? null : JSON.parseObject(body.string());
                if (root == null || !(root.get("results") instanceof JSONArray)) {
                    throw new SearchException("Invalid Context search response");
                }
                if (root.containsKey("partial") && !Boolean.FALSE.equals(root.get("partial"))) {
                    failure = "Context search returned an incomplete response";
                    throw new SearchException(failure);
                }
                JSONArray items = (JSONArray) root.get("results");
                List<SearchResult> results = new ArrayList<>();
                for (Object value : items) {
                    if (!(value instanceof JSONObject)) continue;
                    JSONObject item = (JSONObject) value;
                    if (!(item.get("title") instanceof String) || StringUtil.noText(item.getString("title"))
                        || !(item.get("url") instanceof String)) continue;
                    HttpUrl url = HttpUrl.parse(item.getString("url"));
                    if (url == null || !url.username().isEmpty() || !url.password().isEmpty()) continue;
                    Object rawHighlights = item.get("highlights");
                    if (!(rawHighlights instanceof JSONObject)) continue;
                    JSONObject highlights = (JSONObject) rawHighlights;
                    if (!"SUCCESS".equals(highlights.get("code")) || !(highlights.get("highlights") instanceof JSONArray)) continue;
                    List<String> passages = new ArrayList<>();
                    for (Object passage : (JSONArray) highlights.get("highlights")) {
                        if (passage instanceof String && StringUtil.hasText((String) passage)) passages.add((String) passage);
                    }
                    if (passages.isEmpty()) continue;
                    results.add(SearchResult.builder().title(item.getString("title")).url(item.getString("url"))
                        .description(String.join("\n", passages)).build());
                    if (results.size() == count) break;
                }
                if (!items.isEmpty() && results.isEmpty()) {
                    failure = "Context search returned no usable Highlights";
                    throw new SearchException(failure);
                }
                return results;
            }
        } catch (Exception ignored) {
            // WebSearchTool logs the full exception, so never retain provider payloads or causes.
            throw new SearchException(failure);
        }
    }
}

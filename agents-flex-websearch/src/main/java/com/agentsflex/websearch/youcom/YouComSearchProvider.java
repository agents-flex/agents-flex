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
package com.agentsflex.websearch.youcom;

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

/**
 * You.com Web Search provider.
 *
 * <p>Requires a You.com API key: https://you.com/platform/api-keys
 */
public class YouComSearchProvider implements SearchProvider {

    private static final String BASE_URL = "https://ydc-index.io/v1/search";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");

    private final String apiKey;
    private final OkHttpClient httpClient;

    public YouComSearchProvider(String apiKey) {
        this(apiKey, new OkHttpClient());
    }


    public YouComSearchProvider(String apiKey, OkHttpClient httpClient) {

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
            JSONObject results = root.getJSONObject("results");

            if (results == null) {
                return Collections.emptyList();
            }

            JSONArray arr = results.getJSONArray("web");

            return parse(arr);
        } catch (SearchException e) {
            throw e;
        } catch (Exception e) {
            throw new SearchException("Failed to search with You.com", e);
        }
    }

    // ---------------------------------------------------
    // HTTP
    // ---------------------------------------------------

    private String execute(SearchRequest request) throws IOException {

        JSONObject payload = new JSONObject();
        payload.put("query", request.getQuery());
        payload.put("count", request.getMaxResults());

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
            .addHeader("Content-Type", "application/json")
            .addHeader("X-API-Key", apiKey)
            .post(requestBody)
            .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {

            ResponseBody responseBody = response.body();
            String body = responseBody != null ? responseBody.string() : "";

            if (!response.isSuccessful()) {
                throw new SearchException("You.com Search HTTP Error: " + response.code()
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
            if (item == null) continue;

            String title = item.getString("title");
            String url = item.getString("url");
            String description = item.getString("description");

            if (StringUtil.noText(title) || StringUtil.noText(url)) {
                continue;
            }

            SearchResult r = new SearchResult();
            r.setTitle(title);
            r.setUrl(url);
            r.setDescription(description);

            results.add(r);
        }

        return results;
    }
}

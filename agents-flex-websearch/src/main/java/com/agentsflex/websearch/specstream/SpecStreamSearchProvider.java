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
package com.agentsflex.websearch.specstream;

import com.agentsflex.core.model.client.OkHttpClientUtil;
import com.agentsflex.core.util.StringUtil;
import com.agentsflex.websearch.SearchException;
import com.agentsflex.websearch.SearchProvider;
import com.agentsflex.websearch.SearchRequest;
import com.agentsflex.websearch.SearchResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Web search provider backed by the SpecStream Search API ({@code GET /v1/web-search}).
 * <p>
 * Each blocked domain is sent as a {@code -site:} operator in the query. A single allowed
 * domain is sent as {@code site:}; several allowed domains are left to the domain filter in
 * {@link com.agentsflex.websearch.WebSearchTool}, because every operator in the query must
 * hold on each result.
 *
 * @see <a href="https://specstream.ai/docs/search-api">SpecStream Search API</a>
 */
public class SpecStreamSearchProvider implements SearchProvider {

    private static final String BASE_URL = "https://api.specstream.ai/v1/web-search";
    private static final int MAX_NUM = 100;

    private final String apiKey;
    private final OkHttpClient httpClient;
    private String baseUrl = BASE_URL;
    private Boolean fast;

    public SpecStreamSearchProvider(String apiKey) {
        this(apiKey, OkHttpClientUtil.buildDefaultClient());
    }

    public SpecStreamSearchProvider(String apiKey, OkHttpClient httpClient) {
        if (StringUtil.noText(apiKey)) {
            throw new IllegalArgumentException("SpecStream apiKey must not be null or blank");
        }
        if (httpClient == null) {
            throw new IllegalArgumentException("OkHttpClient must not be null");
        }

        this.apiKey = apiKey;
        this.httpClient = httpClient;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        if (StringUtil.noText(baseUrl)) {
            throw new IllegalArgumentException("baseUrl must not be null or blank");
        }
        this.baseUrl = baseUrl;
    }

    public Boolean getFast() {
        return fast;
    }

    /**
     * When true, sends {@code speed=fast}: the engines' results with their snippets in about
     * a second, without the extra reading of the default mode.
     */
    public void setFast(Boolean fast) {
        this.fast = fast;
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
            JSONObject data = root == null ? null : root.getJSONObject("data");
            return data == null ? Collections.<SearchResult>emptyList() : parse(data.getJSONArray("sources"));
        } catch (SearchException e) {
            throw e;
        } catch (Exception e) {
            throw new SearchException("Failed to search with SpecStream", e);
        }
    }

    private String execute(SearchRequest request) throws IOException {
        HttpUrl parsed = HttpUrl.parse(baseUrl);
        if (parsed == null) {
            throw new SearchException("Invalid SpecStream baseUrl: " + baseUrl);
        }

        HttpUrl.Builder url = parsed.newBuilder()
            .addQueryParameter("q", buildQuery(request));
        Integer maxResults = request.getMaxResults();
        if (maxResults != null && maxResults > 0) {
            url.addQueryParameter("num", String.valueOf(Math.min(maxResults, MAX_NUM)));
        }
        if (Boolean.TRUE.equals(fast)) {
            url.addQueryParameter("speed", "fast");
        }

        Request httpRequest = new Request.Builder()
            .url(url.build())
            .addHeader("Accept", "application/json")
            .addHeader("Authorization", "Bearer " + apiKey)
            .get()
            .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            ResponseBody responseBody = response.body();
            String body = responseBody != null ? responseBody.string() : "";
            if (!response.isSuccessful()) {
                throw new SearchException("SpecStream Search HTTP Error: " + response.code()
                    + (StringUtil.hasText(body) ? ", body=" + body : ""));
            }
            return body;
        }
    }

    static String buildQuery(SearchRequest request) {
        StringBuilder query = new StringBuilder(request.getQuery().trim());

        List<String> allowed = request.getAllowedDomains();
        if (allowed != null && allowed.size() == 1 && StringUtil.hasText(allowed.get(0))) {
            query.append(" site:").append(allowed.get(0).trim());
        }

        List<String> blocked = request.getBlockedDomains();
        if (blocked != null) {
            for (String domain : blocked) {
                if (StringUtil.hasText(domain)) {
                    query.append(" -site:").append(domain.trim());
                }
            }
        }
        return query.toString();
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

            String snippet = item.getString("snippet");
            result.setDescription(StringUtil.hasText(snippet) ? snippet : item.getString("excerpt"));

            String published = item.getString("published");
            if (StringUtil.hasText(published)) {
                result.putMetadata("published", published);
            }
            results.add(result);
        }
        return results;
    }
}

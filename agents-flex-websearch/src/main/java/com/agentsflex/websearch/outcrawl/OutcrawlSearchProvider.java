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
package com.agentsflex.websearch.outcrawl;

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
 * Web search provider backed by the Outcrawl Search API.
 * <p>
 * The API takes only {@code query} and {@code limit}, so the allowed and blocked domain lists
 * of {@link SearchRequest} are not sent.
 *
 * @see <a href="https://outcrawl.ai">Outcrawl</a>
 */
public class OutcrawlSearchProvider implements SearchProvider {

    private static final String BASE_URL = "https://api.outcrawl.ai/v1/search";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");

    private final String apiKey;
    private final OkHttpClient httpClient;

    public OutcrawlSearchProvider(String apiKey) {
        this(apiKey, OkHttpClientUtil.buildDefaultClient());
    }

    public OutcrawlSearchProvider(String apiKey, OkHttpClient httpClient) {
        if (StringUtil.noText(apiKey)) {
            throw new IllegalArgumentException("Outcrawl apiKey must not be null or blank");
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
            throw new IllegalArgumentException("query keyword is null or blank.");
        }

        try {
            String body = execute(request);
            if (StringUtil.noText(body)) {
                return Collections.emptyList();
            }

            JSONObject root = JSON.parseObject(body);
            return root == null ? Collections.<SearchResult>emptyList() : parse(root.getJSONArray("hits"));
        } catch (SearchException e) {
            throw e;
        } catch (Exception e) {
            throw new SearchException("Failed to search with Outcrawl", e);
        }
    }

    private String execute(SearchRequest request) throws IOException {
        JSONObject payload = new JSONObject();
        payload.put("query", request.getQuery());
        if (request.getMaxResults() != null) {
            payload.put("limit", request.getMaxResults());
        }

        RequestBody requestBody = RequestBody.create(payload.toJSONString(), JSON_MEDIA_TYPE);
        Request httpRequest = new Request.Builder()
            .url(BASE_URL)
            .addHeader("Accept", "application/json")
            .addHeader("Authorization", "Bearer " + apiKey)
            .post(requestBody)
            .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            ResponseBody responseBody = response.body();
            String body = responseBody != null ? responseBody.string() : "";
            if (!response.isSuccessful()) {
                throw new SearchException("Outcrawl Search HTTP Error: " + response.code()
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
            result.setDescription(item.getString("snippet"));
            results.add(result);
        }
        return results;
    }
}

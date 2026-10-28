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
package com.agentsflex.websearch.serply;

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

public class SerplySearchProvider implements SearchProvider {

    private static final String BASE_URL = "https://api.serply.io/v1/search/";

    private final String apiKey;
    private final OkHttpClient httpClient;

    public SerplySearchProvider(String apiKey) {
        this(apiKey, OkHttpClientUtil.buildDefaultClient());
    }

    public SerplySearchProvider(String apiKey, OkHttpClient httpClient) {
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

        int count = request.getMaxResults() != null ? request.getMaxResults() : 10;
        HttpUrl url = HttpUrl.get(BASE_URL).newBuilder()
            .addQueryParameter("q", request.getQuery())
            .addQueryParameter("num", String.valueOf(count))
            .build();

        Request httpRequest = new Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
            .addHeader("X-Api-Key", apiKey)
            // Serply's edge rejects some default client user agents.
            .addHeader("User-Agent", "agents-flex")
            .get()
            .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            ResponseBody responseBody = response.body();
            String body = responseBody != null ? responseBody.string() : "";
            if (!response.isSuccessful()) {
                throw new SearchException("Serply HTTP Error: " + response.code()
                    + (StringUtil.hasText(body) ? ", body=" + body : ""));
            }
            return parse(body);
        } catch (SearchException e) {
            throw e;
        } catch (Exception e) {
            throw new SearchException(e);
        }
    }

    private List<SearchResult> parse(String body) {
        List<SearchResult> results = new ArrayList<>();
        JSONObject root = StringUtil.hasText(body) ? JSON.parseObject(body) : null;
        JSONArray array = root != null ? root.getJSONArray("results") : null;
        if (array == null) {
            return results;
        }
        for (int i = 0; i < array.size(); i++) {
            JSONObject item = array.getJSONObject(i);
            if (item == null || StringUtil.noText(item.getString("title")) || StringUtil.noText(item.getString("link"))) {
                continue;
            }
            results.add(SearchResult.builder()
                .title(item.getString("title"))
                .url(item.getString("link"))
                .description(item.getString("description"))
                .build());
        }
        return results;
    }
}

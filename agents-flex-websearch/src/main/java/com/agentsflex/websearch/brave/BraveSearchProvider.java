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
package com.agentsflex.websearch.brave;

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

public class BraveSearchProvider implements SearchProvider {

    private static final String BASE_URL = "https://api.search.brave.com/res/v1/web/search";

    private final String apiKey;
    private final OkHttpClient httpClient;

    public BraveSearchProvider(String apiKey) {
        this(apiKey, OkHttpClientUtil.buildDefaultClient());
    }


    public BraveSearchProvider(String apiKey, OkHttpClient httpClient) {

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
            String body = execute(request.getQuery(), request.getMaxResults());

            if (StringUtil.noText(body)) {
                return Collections.emptyList();
            }

            JSONObject root = JSON.parseObject(body);

            List<SearchResult> results = new ArrayList<>();

            JSONObject web = root.getJSONObject("web");
            if (web != null) {
                JSONArray arr = web.getJSONArray("results");
                results.addAll(parse(arr));
            }

            JSONObject videos = root.getJSONObject("videos");
            if (videos != null) {
                JSONArray arr = videos.getJSONArray("results");
                results.addAll(parse(arr));
            }

            return results;
        } catch (Exception e) {
            throw new SearchException(e);
        }
    }


    private String execute(String query, int resultCount) throws IOException {

        HttpUrl base = HttpUrl.get(BASE_URL);

        HttpUrl url = base.newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("count", String.valueOf(resultCount)).build();

        Request request = new Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
            .addHeader("Accept-Encoding", "gzip")
            .addHeader("X-Subscription-Token", apiKey)
            .get()
            .build();

        try (Response response = httpClient.newCall(request).execute()) {

            if (!response.isSuccessful()) {
                return "";
            }

            ResponseBody body = response.body();
            return body != null ? body.string() : "";
        }
    }

    // ---------------------------------------------------
    // PARSE (fastjson2)
    // ---------------------------------------------------

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
            String desc = item.getString("description");

            if (isBlank(title) || isBlank(url)) {
                continue;
            }

            SearchResult r = new SearchResult();
            r.setTitle(title);
            r.setUrl(url);
            r.setDescription(desc);

            results.add(r);
        }

        return results;
    }

    private boolean isBlank(String s) {
        return !StringUtil.hasText(s);
    }


}

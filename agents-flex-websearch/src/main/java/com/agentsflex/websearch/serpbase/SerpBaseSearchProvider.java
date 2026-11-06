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
package com.agentsflex.websearch.serpbase;

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

/**
 * SerpBase Google web search provider.
 * <p>
 * A failed request still answers with HTTP 200 and carries a non-zero business {@code status}
 * in the JSON envelope, so {@code status} must be checked before {@code organic} is read;
 * otherwise an invalid key or an empty credit balance would look like "no results".
 * See <a href="https://serpbase.dev/docs">https://serpbase.dev/docs</a>.
 */
public class SerpBaseSearchProvider implements SearchProvider {

    private static final String BASE_URL = "https://api.serpbase.dev/google/search";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");

    private final String apiKey;
    private final OkHttpClient httpClient;

    private String gl = "us";
    private String hl = "en";

    public SerpBaseSearchProvider(String apiKey) {
        this(apiKey, OkHttpClientUtil.buildDefaultClient());
    }

    public SerpBaseSearchProvider(String apiKey, OkHttpClient httpClient) {

        if (StringUtil.noText(apiKey)) {
            throw new IllegalArgumentException("apiKey must not be empty");
        }

        if (httpClient == null) {
            throw new IllegalArgumentException("OkHttpClient must not be null");
        }

        this.apiKey = apiKey;
        this.httpClient = httpClient;
    }

    public String getGl() {
        return gl;
    }

    public void setGl(String gl) {
        this.gl = gl;
    }

    public String getHl() {
        return hl;
    }

    public void setHl(String hl) {
        this.hl = hl;
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

            return parse(body);
        } catch (SearchException e) {
            throw e;
        } catch (Exception e) {
            throw new SearchException(e);
        }
    }

    private String execute(SearchRequest request) throws IOException {

        int count = request.getMaxResults() != null ? request.getMaxResults() : 10;

        JSONObject payload = new JSONObject();
        payload.put("q", request.getQuery());
        payload.put("gl", gl);
        payload.put("hl", hl);
        payload.put("num", count);

        RequestBody requestBody = RequestBody.create(payload.toJSONString(), JSON_MEDIA_TYPE);

        Request httpRequest = new Request.Builder()
            .url(BASE_URL)
            .addHeader("Accept", "application/json")
            // SerpBase rejects keys passed as a query-string parameter.
            .addHeader("X-API-Key", apiKey)
            .post(requestBody)
            .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {

            ResponseBody responseBody = response.body();
            String body = responseBody != null ? responseBody.string() : "";
            if (!response.isSuccessful()) {
                throw new SearchException("SerpBase Search HTTP Error: " + response.code()
                    + (StringUtil.hasText(body) ? ", body=" + body : ""));
            }
            return body;
        }
    }

    private List<SearchResult> parse(String body) {

        JSONObject root = JSON.parseObject(body);
        Integer status = root != null ? root.getInteger("status") : null;

        // HTTP 200 is not success: check the envelope before touching "organic".
        if (status == null || status != 0) {
            throw new SearchException("SerpBase Search Error: status=" + status
                + (StringUtil.hasText(body) ? ", body=" + body : ""));
        }

        JSONArray array = root.getJSONArray("organic");
        if (array == null || array.isEmpty()) {
            return Collections.emptyList();
        }

        List<SearchResult> results = new ArrayList<>();

        for (int i = 0; i < array.size(); i++) {

            JSONObject item = array.getJSONObject(i);
            if (item == null) continue;

            String title = item.getString("title");
            String link = item.getString("link");
            String snippet = item.getString("snippet");

            if (StringUtil.noText(title) || StringUtil.noText(link)) {
                continue;
            }

            results.add(SearchResult.builder()
                .title(title)
                .url(link)
                .description(snippet)
                .build());
        }

        return results;
    }
}

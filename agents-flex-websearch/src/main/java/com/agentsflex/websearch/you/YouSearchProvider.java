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
package com.agentsflex.websearch.you;

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
 * Web search provider backed by the You.com Search API.
 * <p>
 * Works without an API key: requests go to the free keyless endpoint
 * (100 searches a day, counted per IP). With an API key, requests go to the
 * keyed endpoint for higher limits.
 * <p>
 * Allowed and blocked domains are applied with {@code site:} and {@code -site:}
 * query operators.
 *
 * @see <a href="https://you.com/docs/api-reference/search/v1-search?utm_source=agents-flex-agents-flex&amp;utm_medium=oss_integration&amp;utm_campaign=2026-10-oss-integrations&amp;utm_content=docs">You.com API documentation</a>
 */
public class YouSearchProvider implements SearchProvider {

    static final String KEYLESS_URL = "https://api.you.com/v1/agents/search";
    static final String KEYED_URL = "https://ydc-index.io/v1/search";
    static final String USER_AGENT = "youdotcom-integration/agents-flex-agents-flex";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");

    private final String apiKey;
    private final OkHttpClient httpClient;

    public YouSearchProvider() {
        this(null, OkHttpClientUtil.buildDefaultClient());
    }

    public YouSearchProvider(String apiKey) {
        this(apiKey, OkHttpClientUtil.buildDefaultClient());
    }

    public YouSearchProvider(OkHttpClient httpClient) {
        this(null, httpClient);
    }

    public YouSearchProvider(String apiKey, OkHttpClient httpClient) {
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
            JSONObject results = root.getJSONObject("results");
            return results == null ? Collections.emptyList() : parse(results.getJSONArray("web"));
        } catch (SearchException e) {
            throw e;
        } catch (Exception e) {
            throw new SearchException("Failed to search with You.com", e);
        }
    }

    static String buildQuery(SearchRequest request) {
        StringBuilder query = new StringBuilder(request.getQuery().trim());

        List<String> allowedDomains = request.getAllowedDomains();
        if (allowedDomains != null && !allowedDomains.isEmpty()) {
            List<String> sites = new ArrayList<>();
            for (String domain : allowedDomains) {
                if (StringUtil.hasText(domain)) {
                    sites.add("site:" + domain.trim());
                }
            }
            if (sites.size() == 1) {
                query.append(' ').append(sites.get(0));
            } else if (sites.size() > 1) {
                query.append(" (").append(String.join(" OR ", sites)).append(')');
            }
        }

        List<String> blockedDomains = request.getBlockedDomains();
        if (blockedDomains != null) {
            for (String domain : blockedDomains) {
                if (StringUtil.hasText(domain)) {
                    query.append(" -site:").append(domain.trim());
                }
            }
        }
        return query.toString();
    }

    private String execute(SearchRequest request) throws IOException {
        String query = buildQuery(request);
        int count = request.getMaxResults() != null ? request.getMaxResults() : 10;

        Request.Builder requestBuilder = new Request.Builder()
            .addHeader("Accept", "application/json")
            .addHeader("User-Agent", USER_AGENT);

        if (StringUtil.hasText(apiKey)) {
            JSONObject payload = new JSONObject();
            payload.put("query", query);
            payload.put("count", count);
            requestBuilder.url(KEYED_URL)
                .addHeader("X-API-Key", apiKey)
                .post(RequestBody.create(payload.toJSONString(), JSON_MEDIA_TYPE));
        } else {
            HttpUrl url = HttpUrl.get(KEYLESS_URL).newBuilder()
                .addQueryParameter("query", query)
                .addQueryParameter("count", String.valueOf(count))
                .build();
            requestBuilder.url(url).get();
        }

        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            ResponseBody responseBody = response.body();
            String body = responseBody != null ? responseBody.string() : "";
            if (!response.isSuccessful()) {
                throw new SearchException(errorMessage(response, body));
            }
            return body;
        }
    }

    private String errorMessage(Response response, String body) {
        int code = response.code();
        String hint;
        switch (code) {
            case 401:
            case 403:
                hint = "invalid or unauthorized API key";
                break;
            case 402:
                hint = "out of credits";
                break;
            case 429:
                String retryAfter = response.header("Retry-After");
                hint = "rate limited" + (StringUtil.hasText(retryAfter) ? ", retry after " + retryAfter + "s" : "")
                    + (StringUtil.hasText(apiKey) ? "" : "; the keyless tier allows 100 searches a day, pass an API key for more");
                break;
            default:
                hint = null;
        }
        return "You.com Search HTTP Error: " + code
            + (hint != null ? " (" + hint + ")" : "")
            + (StringUtil.hasText(body) ? ", body=" + body : "");
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

            String description = item.getString("description");
            JSONArray snippets = item.getJSONArray("snippets");
            if (StringUtil.noText(description) && snippets != null && !snippets.isEmpty()) {
                description = snippets.getString(0);
            }

            SearchResult result = new SearchResult();
            result.setTitle(title);
            result.setUrl(url);
            result.setDescription(description);
            String pageAge = item.getString("page_age");
            if (StringUtil.hasText(pageAge)) {
                result.putMetadata("page_age", pageAge);
            }
            results.add(result);
        }
        return results;
    }
}

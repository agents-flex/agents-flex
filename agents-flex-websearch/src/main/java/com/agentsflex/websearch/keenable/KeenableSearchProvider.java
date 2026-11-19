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
package com.agentsflex.websearch.keenable;

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
 * Web search provider backed by the Keenable Search API.
 * <p>
 * Works without an API key: requests go to the keyless public endpoint, which is rate limited
 * per IP and needs an application name in the {@code X-Keenable-Title} header. With an API key,
 * requests go to the keyed endpoint with {@code X-API-Key}.
 * <p>
 * One allowed domain is sent as {@code site}; several are sent as {@code site:} operators
 * joined with {@code OR} in the query. Blocked domains are sent as {@code exclude_domains}.
 *
 * @see <a href="https://docs.keenable.ai/api-reference/search">Keenable Search API</a>
 */
public class KeenableSearchProvider implements SearchProvider {

    static final String PUBLIC_URL = "https://api.keenable.ai/v1/search/public";
    static final String KEYED_URL = "https://api.keenable.ai/v1/search";
    static final String DEFAULT_APP_TITLE = "Agents-Flex";
    private static final int MAX_RESULTS_LIMIT = 50;
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");

    private final String apiKey;
    private final String appTitle;
    private final OkHttpClient httpClient;

    public KeenableSearchProvider() {
        this(null, OkHttpClientUtil.buildDefaultClient());
    }

    public KeenableSearchProvider(String apiKey) {
        this(apiKey, OkHttpClientUtil.buildDefaultClient());
    }

    public KeenableSearchProvider(OkHttpClient httpClient) {
        this(null, httpClient);
    }

    public KeenableSearchProvider(String apiKey, OkHttpClient httpClient) {
        this(apiKey, DEFAULT_APP_TITLE, httpClient);
    }

    /**
     * @param apiKey     Keenable API key, or {@code null} / blank for the keyless endpoint
     * @param appTitle   application name sent as {@code X-Keenable-Title} on keyless requests
     * @param httpClient OkHttp client
     */
    public KeenableSearchProvider(String apiKey, String appTitle, OkHttpClient httpClient) {
        if (httpClient == null) {
            throw new IllegalArgumentException("OkHttpClient must not be null");
        }

        this.apiKey = StringUtil.hasText(apiKey) ? apiKey.trim() : null;
        this.appTitle = StringUtil.hasText(appTitle) ? appTitle.trim() : DEFAULT_APP_TITLE;
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
            return root == null ? Collections.<SearchResult>emptyList() : parse(root.getJSONArray("results"));
        } catch (SearchException e) {
            throw e;
        } catch (Exception e) {
            throw new SearchException("Failed to search with Keenable", e);
        }
    }

    static JSONObject buildPayload(SearchRequest request) {
        JSONObject payload = new JSONObject();
        String query = request.getQuery().trim();

        List<String> sites = cleanDomains(request.getAllowedDomains());
        if (sites.size() == 1) {
            payload.put("site", sites.get(0));
        } else if (sites.size() > 1) {
            List<String> operators = new ArrayList<>();
            for (String site : sites) {
                operators.add("site:" + site);
            }
            query = query + " (" + String.join(" OR ", operators) + ")";
        }
        payload.put("query", query);

        List<String> blocked = cleanDomains(request.getBlockedDomains());
        if (!blocked.isEmpty()) {
            payload.put("exclude_domains", blocked);
        }

        Integer maxResults = request.getMaxResults();
        if (maxResults != null && maxResults > 0) {
            payload.put("max_results", Math.min(maxResults, MAX_RESULTS_LIMIT));
        }
        return payload;
    }

    private static List<String> cleanDomains(List<String> domains) {
        if (domains == null || domains.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> cleaned = new ArrayList<>();
        for (String domain : domains) {
            if (StringUtil.hasText(domain)) {
                cleaned.add(domain.trim());
            }
        }
        return cleaned;
    }

    private String execute(SearchRequest request) throws IOException {
        RequestBody requestBody = RequestBody.create(buildPayload(request).toJSONString(), JSON_MEDIA_TYPE);
        Request.Builder builder = new Request.Builder()
            .addHeader("Accept", "application/json")
            .post(requestBody);
        if (apiKey != null) {
            builder.url(KEYED_URL).addHeader("X-API-Key", apiKey);
        } else {
            builder.url(PUBLIC_URL).addHeader("X-Keenable-Title", appTitle);
        }

        try (Response response = httpClient.newCall(builder.build()).execute()) {
            ResponseBody responseBody = response.body();
            String body = responseBody != null ? responseBody.string() : "";
            if (!response.isSuccessful()) {
                throw new SearchException("Keenable Search HTTP Error: " + response.code()
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

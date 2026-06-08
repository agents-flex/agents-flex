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
package com.agentsflex.websearch.baidu;

import com.agentsflex.core.util.StringUtil;
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
 * 百度千帆 Web Search Provider
 * <p>
 * 基于百度千帆平台的百度搜索 API 实现
 * 文档地址: https://cloud.baidu.com/doc/qianfan-api/s/Wmbq4z7e5
 *
 * @author Michael Yang
 */
public class BaiduQianfanSearchProvider implements SearchProvider {

    private static final String BASE_URL = "https://qianfan.baidubce.com/v2/ai_search/web_search";

    private final String apiKey;
    private final OkHttpClient httpClient;

    /**
     * 搜索版本: standard(完整版本) 或 lite(标准版本，时延更好)
     */
    private String edition = "standard";

    /**
     * 资源类型过滤: web, video, image, aladdin
     */
    private List<String> resourceTypeFilter;

    /**
     * 是否启用安全搜索
     */
    private Boolean safeSearch;

    public BaiduQianfanSearchProvider(String apiKey) {
        this(apiKey, new OkHttpClient());
    }

    public BaiduQianfanSearchProvider(String apiKey, OkHttpClient httpClient) {
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

    public String getEdition() {
        return edition;
    }

    public void setEdition(String edition) {
        this.edition = edition;
    }

    public List<String> getResourceTypeFilter() {
        return resourceTypeFilter;
    }

    public void setResourceTypeFilter(List<String> resourceTypeFilter) {
        this.resourceTypeFilter = resourceTypeFilter;
    }

    public Boolean getSafeSearch() {
        return safeSearch;
    }

    public void setSafeSearch(Boolean safeSearch) {
        this.safeSearch = safeSearch;
    }

    @Override
    public List<SearchResult> search(SearchRequest request) {

        if (request == null || StringUtil.noText(request.getQuery())) {
            return Collections.emptyList();
        }

        try {
            // 百度 API 限制 query 最多 72 个字符
            String query = request.getQuery();
            if (query.length() > 72) {
                query = query.substring(0, 72);
            }

            String body = execute(query, request);

            if (StringUtil.noText(body)) {
                return Collections.emptyList();
            }

            JSONObject root = JSON.parseObject(body);

            // 检查是否有错误
            Integer errorCode = root.getInteger("error_code");
            if (errorCode != null && errorCode != 0) {
                String errorMsg = root.getString("error_msg");
                System.err.println("Baidu Qianfan Search Error: code=" + errorCode + ", msg=" + errorMsg);
                return Collections.emptyList();
            }

            JSONArray references = root.getJSONArray("references");
            if (references == null || references.isEmpty()) {
                return Collections.emptyList();
            }

            return parse(references);

        } catch (Exception e) {
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    // ---------------------------------------------------
    // HTTP Request
    // ---------------------------------------------------

    private String execute(String query, SearchRequest request) throws IOException {

        // 构建 messages 数组
        JSONArray messages = new JSONArray();
        JSONObject message = new JSONObject();
        message.put("role", "user");
        message.put("content", query);
        messages.add(message);

        // 构建请求体
        JSONObject bodyJson = new JSONObject();
        bodyJson.put("messages", messages);

        // 可选参数: edition
        if (StringUtil.hasText(edition)) {
            bodyJson.put("edition", edition);
        }

        // 构建 resource_type_filter
        if (resourceTypeFilter != null && !resourceTypeFilter.isEmpty()) {
            bodyJson.put("resource_type_filter", resourceTypeFilter);
        }

        // 构建 search_filter
        JSONObject searchFilter = new JSONObject();

        // 处理 allowed_domains -> site
        if (request.getAllowedDomains() != null && !request.getAllowedDomains().isEmpty()) {
            JSONObject match = new JSONObject();
            match.put("site", request.getAllowedDomains());
            searchFilter.put("match", match);
        }

        // 如果 search_filter 不为空，则添加到请求体
        if (!searchFilter.isEmpty()) {
            bodyJson.put("search_filter", searchFilter);
        }


        // 处理 blocked_domains -> block_websites
        if (request.getBlockedDomains() != null && !request.getBlockedDomains().isEmpty()) {
            bodyJson.put("block_websites", request.getBlockedDomains());
        }


        // 安全搜索
        if (safeSearch != null) {
            bodyJson.put("safe_search", safeSearch);
        }

        RequestBody body = RequestBody.create(
            bodyJson.toJSONString(),
            MediaType.parse("application/json")
        );

        Request httpRequest = new Request.Builder()
            .url(BASE_URL)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer " + apiKey)
            .post(body)
            .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {

            if (!response.isSuccessful()) {
                System.err.println("HTTP Error: " + response.code() + " - " + response.message());
                return null;
            }

            ResponseBody rb = response.body();
            return rb != null ? rb.string() : null;
        }
    }

    // ---------------------------------------------------
    // Parse Response
    // ---------------------------------------------------

    private List<SearchResult> parse(JSONArray references) {

        if (references == null || references.isEmpty()) {
            return Collections.emptyList();
        }

        List<SearchResult> list = new ArrayList<>();

        for (int i = 0; i < references.size(); i++) {

            JSONObject item = references.getJSONObject(i);
            if (item == null) continue;

            String title = item.getString("title");
            String url = item.getString("url");
            String content = item.getString("content");
            String date = item.getString("date");
            String website = item.getString("website");

            // 跳过无效结果
            if (StringUtil.noText(title) || StringUtil.noText(url)) {
                continue;
            }

            SearchResult result = SearchResult.builder()
                .title(title)
                .url(url)
                .description(content)
                .build();

            // 添加额外信息到 frontMatter
            if (StringUtil.hasText(date)) {
                result.setFrontMatter(new java.util.HashMap<>());
                result.getFrontMatter().put("date", date);
            }

            if (StringUtil.hasText(website)) {
                if (result.getFrontMatter() == null) {
                    result.setFrontMatter(new java.util.HashMap<>());
                }
                result.getFrontMatter().put("website", website);
            }

            list.add(result);
        }

        return list;
    }
}

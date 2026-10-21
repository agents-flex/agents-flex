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
package com.agentsflex.websearch.parallel;

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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Web search provider backed by the free, anonymous Parallel Search MCP server.
 *
 * <p>This provider uses MCP Streamable HTTP and does not require an API key.
 * Scope an instance to a conversation so related searches reuse its session id.</p>
 */
public class ParallelSearchProvider implements SearchProvider {

    private static final String DEFAULT_ENDPOINT = "https://search.parallel.ai/mcp";
    private static final String USER_AGENT = "Agents-Flex";
    private static final String PROTOCOL_VERSION = "2025-03-26";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");

    private final String endpoint;
    private final OkHttpClient httpClient;
    private final String sessionId = UUID.randomUUID().toString();

    public ParallelSearchProvider() {
        this(DEFAULT_ENDPOINT, OkHttpClientUtil.buildDefaultClient());
    }

    /**
     * Creates a provider that connects to the given MCP Streamable HTTP endpoint.
     *
     * @param endpoint MCP endpoint URL
     */
    public ParallelSearchProvider(String endpoint) {
        this(endpoint, OkHttpClientUtil.buildDefaultClient());
    }

    /**
     * Creates a provider with a caller-supplied HTTP client.
     *
     * @param httpClient HTTP client
     */
    public ParallelSearchProvider(OkHttpClient httpClient) {
        this(DEFAULT_ENDPOINT, httpClient);
    }

    /**
     * Creates a provider with a caller-supplied endpoint and HTTP client.
     *
     * @param endpoint MCP endpoint URL
     * @param httpClient HTTP client
     */
    public ParallelSearchProvider(String endpoint, OkHttpClient httpClient) {
        if (StringUtil.noText(endpoint)) {
            throw new IllegalArgumentException("MCP endpoint must not be blank");
        }
        if (httpClient == null) {
            throw new IllegalArgumentException("OkHttpClient must not be null");
        }
        this.endpoint = endpoint.trim();
        this.httpClient = httpClient;
    }

    @Override
    public List<SearchResult> search(SearchRequest request) {
        if (request == null || StringUtil.noText(request.getQuery())) {
            throw new IllegalArgumentException("query keyword is null or blank.");
        }

        String sessionHeader = null;
        String protocolVersion = PROTOCOL_VERSION;
        AtomicInteger requestId = new AtomicInteger(1);
        try {
            JSONObject initialize = new JSONObject();
            initialize.put("protocolVersion", PROTOCOL_VERSION);
            initialize.put("capabilities", new JSONObject());
            JSONObject clientInfo = new JSONObject();
            clientInfo.put("name", "agents-flex");
            clientInfo.put("version", "2.3.0");
            initialize.put("clientInfo", clientInfo);

            JSONObject initialized = send("initialize", initialize, requestId.getAndIncrement(), null, null);
            JSONObject initResult = initialized.getJSONObject("result");
            if (initResult == null || StringUtil.noText(initResult.getString("protocolVersion"))) {
                throw new SearchException("Parallel Search MCP did not negotiate a protocol version");
            }
            protocolVersion = initResult.getString("protocolVersion");
            sessionHeader = initialized.getString("_sessionId");

            sendNotification("notifications/initialized", sessionHeader, protocolVersion);

            JSONObject listed = send("tools/list", new JSONObject(), requestId.getAndIncrement(), sessionHeader, protocolVersion);
            JSONArray tools = listed.getJSONObject("result") == null
                ? null : listed.getJSONObject("result").getJSONArray("tools");
            if (!hasTool(tools, "web_search")) {
                throw new SearchException("Parallel Search MCP does not expose the web_search tool");
            }

            JSONObject arguments = new JSONObject();
            arguments.put("objective", request.getQuery());
            arguments.put("search_queries", Collections.singletonList(keywordQuery(request.getQuery())));
            arguments.put("session_id", sessionId);

            JSONObject params = new JSONObject();
            params.put("name", "web_search");
            params.put("arguments", arguments);
            JSONObject called = send("tools/call", params, requestId.getAndIncrement(), sessionHeader, protocolVersion);
            JSONObject result = called.getJSONObject("result");
            if (result == null) {
                throw new SearchException("Parallel Search MCP returned an empty tool result");
            }
            if (Boolean.TRUE.equals(result.getBoolean("isError"))) {
                throw new SearchException("Parallel Search MCP web_search tool returned an error");
            }

            List<SearchResult> results = parseResults(result);
            Integer maxResults = request.getMaxResults();
            if (maxResults != null && maxResults >= 0 && results.size() > maxResults) {
                return new ArrayList<>(results.subList(0, maxResults));
            }
            return results;
        } catch (SearchException e) {
            throw e;
        } catch (Exception e) {
            throw new SearchException("Failed to search with Parallel Search MCP", e);
        } finally {
            if (sessionHeader != null) {
                closeSession(sessionHeader, protocolVersion);
            }
        }
    }

    private JSONObject send(String method, JSONObject params, int id, String sessionHeader, String protocolVersion) throws IOException {
        JSONObject message = new JSONObject();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.put("params", params);
        return post(message, sessionHeader, protocolVersion, false);
    }

    private void sendNotification(String method, String sessionHeader, String protocolVersion) throws IOException {
        JSONObject message = new JSONObject();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        post(message, sessionHeader, protocolVersion, true);
    }

    private JSONObject post(JSONObject message, String sessionHeader, String protocolVersion, boolean notification) throws IOException {
        Request.Builder request = new Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json, text/event-stream")
            .header("User-Agent", USER_AGENT)
            .post(RequestBody.create(message.toJSONString(), JSON_MEDIA_TYPE));
        if (sessionHeader != null) {
            request.header("Mcp-Session-Id", sessionHeader);
        }
        if (protocolVersion != null) {
            request.header("MCP-Protocol-Version", protocolVersion);
        }

        try (Response response = httpClient.newCall(request.build()).execute()) {
            ResponseBody body = response.body();
            String responseBody = body == null ? "" : body.string();
            if (!response.isSuccessful()) {
                throw new SearchException("Parallel Search MCP HTTP error: " + response.code());
            }
            if (notification && response.code() == 202 && StringUtil.noText(responseBody)) {
                return null;
            }
            if (StringUtil.noText(responseBody)) {
                if (notification) return null;
                throw new SearchException("Parallel Search MCP returned an empty response");
            }
            JSONObject parsed = parseRpcResponse(response.header("Content-Type"), responseBody);
            String returnedSessionId = response.header("Mcp-Session-Id");
            if (returnedSessionId != null) {
                parsed.put("_sessionId", returnedSessionId);
            }
            JSONObject error = parsed.getJSONObject("error");
            if (error != null) {
                throw new SearchException("Parallel Search MCP protocol error: " + error.getIntValue("code"));
            }
            return parsed;
        }
    }

    private JSONObject parseRpcResponse(String contentType, String body) {
        if (contentType != null && contentType.toLowerCase().startsWith("text/event-stream")) {
            JSONObject last = null;
            StringBuilder data = new StringBuilder();
            for (String line : body.split("\\r?\\n")) {
                if (line.isEmpty()) {
                    last = parseEventData(data, last);
                    data.setLength(0);
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    data.append(line.substring(5).trim());
                }
            }
            last = parseEventData(data, last);
            if (last == null) {
                throw new SearchException("Parallel Search MCP returned no JSON-RPC event");
            }
            return last;
        }
        return JSON.parseObject(body);
    }

    private JSONObject parseEventData(StringBuilder data, JSONObject previous) {
        if (data.length() == 0 || "[DONE]".equals(data.toString())) return previous;
        Object value = JSON.parse(data.toString());
        return value instanceof JSONObject ? (JSONObject) value : previous;
    }

    private boolean hasTool(JSONArray tools, String name) {
        if (tools == null) return false;
        for (int i = 0; i < tools.size(); i++) {
            JSONObject tool = tools.getJSONObject(i);
            if (tool != null && name.equals(tool.getString("name"))) return true;
        }
        return false;
    }

    private List<SearchResult> parseResults(JSONObject result) {
        JSONObject structured = result.getJSONObject("structuredContent");
        JSONArray values = structured == null ? null : resultArray(structured);
        if (values != null) return toResults(values);

        JSONArray content = result.getJSONArray("content");
        if (content == null) return Collections.emptyList();
        for (int i = 0; i < content.size(); i++) {
            JSONObject item = content.getJSONObject(i);
            if (item == null || !"text".equals(item.getString("type"))) continue;
            String text = item.getString("text");
            if (StringUtil.noText(text)) continue;
            try {
                Object parsed = JSON.parse(text);
                if (parsed instanceof JSONArray) return toResults((JSONArray) parsed);
                if (parsed instanceof JSONObject) {
                    JSONArray fromText = resultArray((JSONObject) parsed);
                    if (fromText != null) return toResults(fromText);
                }
            } catch (Exception ignored) {
                // The result may contain explanatory text in addition to structured content.
            }
        }
        return Collections.emptyList();
    }

    private JSONArray resultArray(JSONObject value) {
        JSONArray results = value.getJSONArray("results");
        if (results == null) results = value.getJSONArray("web");
        if (results == null && value.getJSONObject("data") != null) {
            results = resultArray(value.getJSONObject("data"));
        }
        return results;
    }

    private List<SearchResult> toResults(JSONArray values) {
        if (values == null || values.isEmpty()) return Collections.emptyList();
        List<SearchResult> results = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            JSONObject item = values.getJSONObject(i);
            if (item == null) continue;
            String title = item.getString("title");
            String url = item.getString("url");
            if (StringUtil.noText(url)) continue;
            if (StringUtil.noText(title)) title = url;
            SearchResult result = new SearchResult();
            result.setTitle(title);
            result.setUrl(url);
            result.setDescription(description(item));
            results.add(result);
        }
        return results;
    }

    private String description(JSONObject item) {
        String description = item.getString("description");
        if (StringUtil.hasText(description)) return description;
        String snippet = item.getString("snippet");
        if (StringUtil.hasText(snippet)) return snippet;
        JSONArray excerpts = item.getJSONArray("excerpts");
        if (excerpts == null || excerpts.isEmpty()) return "";
        List<String> values = new ArrayList<>();
        for (int i = 0; i < excerpts.size(); i++) {
            String excerpt = excerpts.getString(i);
            if (StringUtil.hasText(excerpt)) values.add(excerpt);
        }
        return String.join("\n\n", values);
    }

    private String keywordQuery(String query) {
        String[] words = query.trim().split("\\s+");
        if (words.length <= 6) return query.trim();
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) result.append(' ');
            result.append(words[i]);
        }
        return result.toString();
    }

    private void closeSession(String sessionHeader, String protocolVersion) {
        Request request = new Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json, text/event-stream")
            .header("User-Agent", USER_AGENT)
            .header("Mcp-Session-Id", sessionHeader)
            .header("MCP-Protocol-Version", protocolVersion)
            .delete()
            .build();
        try (Response response = httpClient.newCall(request).execute()) {
            // Session termination is best effort; the search result is already complete.
        } catch (Exception ignored) {
            // Some stateless MCP servers do not implement explicit session termination.
        }
    }
}

package com.agentsflex.websearch.parallel;

import com.agentsflex.websearch.SearchException;
import com.agentsflex.websearch.SearchRequest;
import com.agentsflex.websearch.SearchResult;
import com.agentsflex.websearch.WebSearchTool;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.chat.tool.ToolScanner;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ParallelSearchProviderTest {

    private HttpServer server;
    private HttpContext context;
    private final List<JSONObject> requests = new ArrayList<>();
    private final List<String> methods = new ArrayList<>();
    private final List<String> userAgents = new ArrayList<>();
    private final List<String> sessions = new ArrayList<>();
    private final List<String> protocolVersions = new ArrayList<>();
    private final List<String> authorizations = new ArrayList<>();
    private boolean textResponse;

    @Before
    public void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        context = server.createContext("/mcp", this::handle);
        server.start();
    }

    @After
    public void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    public void shouldUseTheMcpSearchToolThroughWebSearchTool() {
        String query = "Find recent Agents-Flex documentation and examples please";
        WebSearchTool tool = WebSearchTool.builder()
            .provider(provider())
            .maxResults(2)
            .build();

        List<Tool> loadedTools = ToolScanner.scan(tool);
        assertEquals(1, loadedTools.size());
        assertEquals("web_search", loadedTools.get(0).getName());
        Map<String, Object> toolArguments = new HashMap<>();
        toolArguments.put("query", query);
        toolArguments.put("allowed_domains", Collections.singletonList("agentsflex.com"));
        toolArguments.put("blocked_domains", null);
        String answer = (String) loadedTools.get(0).invoke(toolArguments);

        assertTrue(answer.contains("# Agents-Flex documentation"));
        assertTrue(answer.contains("URL: https://agentsflex.com/docs"));
        assertTrue(answer.contains("Official framework guide."));
        assertFalse(answer.contains("Other result"));
        assertEquals(Arrays.asList("initialize", "notifications/initialized", "tools/list", "tools/call"), requestMethods());
        assertEquals("DELETE", methods.get(methods.size() - 1));
        for (String userAgent : userAgents) assertEquals("Agents-Flex", userAgent);
        assertNull(sessions.get(0));
        for (int i = 1; i < sessions.size(); i++) assertEquals("test-session", sessions.get(i));
        assertNull(requests.get(0).get("MCP-Protocol-Version"));
        for (int i = 1; i < protocolVersions.size(); i++) {
            assertEquals("2025-03-26", protocolVersions.get(i));
        }

        JSONObject search = requests.get(3);
        JSONObject params = search.getJSONObject("params");
        assertEquals("web_search", params.getString("name"));
        JSONObject arguments = params.getJSONObject("arguments");
        assertEquals(query, arguments.getString("objective"));
        assertEquals(Collections.singletonList("Find recent Agents-Flex documentation and examples"),
            arguments.getJSONArray("search_queries").toJavaList(String.class));
        assertFalse(arguments.getString("session_id").isEmpty());
        assertFalse(arguments.containsKey("model_name"));
        for (String authorization : authorizations) assertNull(authorization);
    }

    @Test
    public void shouldParseTextResultsAndServerSentEvents() {
        textResponse = true;
        SearchRequest request = new SearchRequest();
        request.setQuery("Agents Flex search");
        request.setMaxResults(5);

        List<SearchResult> results = provider().search(request);

        assertEquals(1, results.size());
        assertEquals("Agents-Flex docs", results.get(0).getTitle());
        assertEquals("https://agentsflex.com/docs", results.get(0).getUrl());
        assertEquals("Search usage and provider setup.", results.get(0).getDescription());
    }

    @Test
    public void shouldRejectBlankQueriesBeforeConnecting() {
        SearchRequest request = new SearchRequest();
        request.setQuery("  ");
        try {
            provider().search(request);
            fail("Expected an invalid query to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("query"));
        }
        assertTrue(methods.isEmpty());
    }

    @Test
    public void shouldReportHttpStatusWithoutEchoingResponseBody() {
        server.removeContext(context);
        server.createContext("/mcp", exchange -> {
            byte[] body = "private error detail".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        SearchRequest request = new SearchRequest();
        request.setQuery("status test");

        try {
            provider().search(request);
            fail("Expected an HTTP error");
        } catch (SearchException expected) {
            assertTrue(expected.getMessage().contains("503"));
            assertFalse(expected.getMessage().contains("private error detail"));
        }
    }

    private ParallelSearchProvider provider() {
        return new ParallelSearchProvider("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
    }

    private List<String> requestMethods() {
        List<String> result = new ArrayList<>();
        for (JSONObject request : requests) result.add(request.getString("method"));
        return result;
    }

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        methods.add(method);
        userAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
        sessions.add(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
        protocolVersions.add(exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"));
        authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        if ("DELETE".equals(method)) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }

        ByteArrayOutputStream requestBytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = exchange.getRequestBody().read(buffer)) != -1) requestBytes.write(buffer, 0, count);
        JSONObject request = JSONObject.parseObject(new String(requestBytes.toByteArray(), StandardCharsets.UTF_8));
        requests.add(request);
        String rpcMethod = request.getString("method");
        if ("notifications/initialized".equals(rpcMethod)) {
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            return;
        }

        JSONObject response = new JSONObject();
        response.put("jsonrpc", "2.0");
        response.put("id", request.getInteger("id"));
        JSONObject result = new JSONObject();
        if ("initialize".equals(rpcMethod)) {
            result.put("protocolVersion", "2025-03-26");
            result.put("capabilities", new JSONObject());
            result.put("serverInfo", new JSONObject());
            exchange.getResponseHeaders().set("Mcp-Session-Id", "test-session");
        } else if ("tools/list".equals(rpcMethod)) {
            JSONArray tools = new JSONArray();
            JSONObject tool = new JSONObject();
            tool.put("name", "web_search");
            tools.add(tool);
            result.put("tools", tools);
        } else if ("tools/call".equals(rpcMethod)) {
            JSONArray content = new JSONArray();
            JSONObject text = new JSONObject();
            text.put("type", "text");
            text.put("text", "search complete");
            content.add(text);
            result.put("content", content);
            if (textResponse) {
                JSONObject payload = new JSONObject();
                JSONArray values = new JSONArray();
                values.add(searchResult("Agents-Flex docs", "https://agentsflex.com/docs", "Search usage and provider setup."));
                payload.put("results", values);
                text.put("text", payload.toJSONString());
                result.put("content", content);
            } else {
                JSONObject structured = new JSONObject();
                JSONArray values = new JSONArray();
                values.add(searchResult("Agents-Flex documentation", "https://agentsflex.com/docs", "Official framework guide."));
                values.add(searchResult("Other result", "https://example.com/other", "Filtered by canonical domain selection."));
                structured.put("results", values);
                result.put("structuredContent", structured);
            }
        }
        response.put("result", result);
        byte[] body = response.toJSONString().getBytes(StandardCharsets.UTF_8);
        String contentType = textResponse && "tools/call".equals(rpcMethod)
            ? "text/event-stream" : "application/json";
        exchange.getResponseHeaders().set("Content-Type", contentType);
        if ("text/event-stream".equals(contentType)) {
            body = ("event: message\ndata: " + response.toJSONString() + "\n\n").getBytes(StandardCharsets.UTF_8);
        }
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private JSONObject searchResult(String title, String url, String excerpt) {
        JSONObject result = new JSONObject();
        result.put("title", title);
        result.put("url", url);
        result.put("excerpts", Collections.singletonList(excerpt));
        return result;
    }
}

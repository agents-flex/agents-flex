package com.agentsflex.websearch.context;

import com.agentsflex.websearch.SearchException;
import com.agentsflex.websearch.SearchRequest;
import com.agentsflex.websearch.SearchResult;
import com.agentsflex.websearch.WebSearchTool;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.*;
import okio.Buffer;
import org.junit.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class ContextSearchProviderTest {
    private static final String KEY = "ctxt_synthetic_canary";
    private static final String PAGE = "{\"title\":\"Source\",\"url\":\"https://example.com/one\","
        + "\"highlights\":{\"code\":\"SUCCESS\",\"highlights\":[\"A relevant passage\",\"Second passage\"]}}";

    @Test
    public void shouldUseHighlightsThroughThePublicToolAndForwardDomainFilters() {
        Stub stub = new Stub(200, "{\"results\":[" + PAGE + "]}");
        ContextSearchProvider provider = provider(stub);
        WebSearchTool tool = WebSearchTool.builder().provider(provider).maxResults(2).build();
        String output = tool.webSearch("java agents", Arrays.asList("example.com"), Arrays.asList("blocked.example"));
        assertTrue(output.contains("URL: https://example.com/one"));
        assertTrue(output.contains("A relevant passage\nSecond passage"));
        assertEquals("https://api.context.dev/v1/web/search", stub.request.url().toString());
        assertEquals("POST", stub.request.method());
        assertEquals("Bearer " + KEY, stub.request.header("Authorization"));
        JSONObject body = JSON.parseObject(stub.body);
        assertEquals(2, body.getIntValue("numResults"));
        assertTrue(body.getJSONObject("highlightsOptions").getBooleanValue("enabled"));
        assertEquals(Arrays.asList("example.com"), body.getJSONArray("includeDomains").toJavaList(String.class));
        assertEquals(Arrays.asList("blocked.example"), body.getJSONArray("excludeDomains").toJavaList(String.class));
    }

    @Test
    public void shouldRespectTheLimitAndReturnAnActualEmptyResponse() {
        List<SearchResult> results = provider(new Stub(200, "{\"partial\":false,\"results\":[" + PAGE + "," + PAGE + "]}"))
            .search(request("q", 1));
        assertEquals(1, results.size());
        assertTrue(provider(new Stub(200, "{\"results\":[]}")).search(request("q", null)).isEmpty());
    }

    @Test
    public void shouldOnlyReturnUsableSuccessfulHighlightsAndSourceUrls() {
        String failed = PAGE.replace("SUCCESS", "TIMEOUT");
        String malformed = PAGE.replace("[\"A relevant passage\",\"Second passage\"]", "[null,4,\" \"]");
        String invalidUrl = PAGE.replace("https://example.com/one", "javascript:alert(1)");
        String credentialUrl = PAGE.replace("https://example.com/one", "https://user:secret@example.com/one");
        String body = "{\"results\":[null,1," + failed + "," + malformed + "," + invalidUrl + "," + credentialUrl + "," + PAGE + "]}";
        assertEquals(1, provider(new Stub(200, body)).search(request("q", 10)).size());
        assertThrows(SearchException.class, () -> provider(new Stub(200, "{\"results\":[" + failed + "]}"))
            .search(request("q", 10)));
    }

    @Test
    public void shouldRejectInvalidInputBeforeMakingARequest() {
        Stub stub = new Stub(200, "{\"results\":[]}");
        ContextSearchProvider provider = provider(stub);
        assertThrows(IllegalArgumentException.class, () -> new ContextSearchProvider(" "));
        assertThrows(IllegalArgumentException.class, () -> new ContextSearchProvider(KEY + "\n"));
        assertThrows(IllegalArgumentException.class, () -> new ContextSearchProvider(KEY, null));
        assertThrows(IllegalArgumentException.class, () -> provider.search(null));
        for (SearchRequest request : Arrays.asList(request(" ", 10), request(new String(new char[501]).replace('\0', 'q'), 10),
            request("q", 0), request("q", 101))) {
            assertThrows(IllegalArgumentException.class, () -> provider.search(request));
        }
        assertNull(stub.request);
    }

    @Test
    public void shouldRejectIncompleteOrMalformedCollectionsWithoutRawPayloads() {
        for (String partial : Arrays.asList("true", "0", "\"\"", "null", "\"false\"")) {
            assertSafeFailure(new Stub(200, "{\"partial\":" + partial + ",\"results\":[" + PAGE + "]}"));
        }
        for (String body : Arrays.asList("null", "{}", "{\"results\":{}}", "not-json " + KEY,
            "{\"results\":\"" + KEY + "\"}")) {
            assertSafeFailure(new Stub(200, body));
        }
    }

    @Test
    public void shouldKeepHttpFailuresAndRedirectsSecretFree() {
        for (int status : Arrays.asList(302, 401, 402, 403, 429, 500)) {
            SearchException failure = assertSafeFailure(new Stub(status, "{\"error\":\"" + KEY + " ctxt_\\u0073ynthetic_canary\"}"));
            assertTrue(failure.getMessage().contains(String.valueOf(status)));
        }
    }

    @Test
    public void shouldNotRetainTransportOrParserCausesInToolErrors() {
        Stub stub = new Stub(200, "");
        stub.failure = new IOException(KEY + " ctxt_\\u0073ynthetic_canary", new RuntimeException(KEY));
        SearchException failure = assertSafeFailure(stub);
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        stub.failure = null;
        stub.runtimeFailure = new SearchException(KEY);
        assertSafeFailure(stub);
        String output = new WebSearchTool(provider(stub)).webSearch("q", null, null);
        assertTrue(output.startsWith("ERROR:"));
        assertFalse(output.contains("ctxt_"));
    }

    private static SearchException assertSafeFailure(Stub stub) {
        SearchException failure = assertThrows(SearchException.class, () -> provider(stub).search(request("q", 10)));
        StringWriter stack = new StringWriter();
        failure.printStackTrace(new PrintWriter(stack));
        assertFalse(stack.toString().contains("ctxt_"));
        assertNull(failure.getCause());
        return failure;
    }

    private static ContextSearchProvider provider(Stub stub) {
        return new ContextSearchProvider(KEY, new OkHttpClient.Builder().addInterceptor(stub).build());
    }

    private static SearchRequest request(String query, Integer max) {
        SearchRequest request = new SearchRequest();
        request.setQuery(query);
        request.setMaxResults(max);
        return request;
    }

    private static class Stub implements Interceptor {
        final int status;
        final String response;
        Request request;
        String body;
        IOException failure;
        RuntimeException runtimeFailure;
        Stub(int status, String response) { this.status = status; this.response = response; }
        @Override
        public Response intercept(Chain chain) throws IOException {
            if (failure != null) throw failure;
            if (runtimeFailure != null) throw runtimeFailure;
            request = chain.request();
            Buffer buffer = new Buffer();
            request.body().writeTo(buffer);
            body = buffer.readUtf8();
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .header("Location", "https://untrusted.example/")
                .body(ResponseBody.create(response, MediaType.parse("application/json"))).build();
        }
    }
}

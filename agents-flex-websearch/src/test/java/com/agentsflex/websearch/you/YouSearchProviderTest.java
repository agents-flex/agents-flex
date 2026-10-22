package com.agentsflex.websearch.you;

import com.agentsflex.websearch.SearchException;
import com.agentsflex.websearch.SearchRequest;
import com.agentsflex.websearch.SearchResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class YouSearchProviderTest {

    private static final String BODY = "{\"results\":{\"web\":["
        + "{\"url\":\"https://example.com/one\",\"title\":\"First\",\"description\":\"Summary\","
        + "\"snippets\":[\"s1\"],\"page_age\":\"2026-10-01T00:00:00\"},"
        + "{\"url\":\"https://example.com/two\",\"title\":\"Second\",\"snippets\":[\"Only snippet\"]},"
        + "{\"url\":\"https://example.com/missing-title\"}]}}";

    @Test
    public void shouldUseKeylessEndpointWithoutApiKey() {
        StubInterceptor interceptor = new StubInterceptor(200, BODY);
        YouSearchProvider provider = new YouSearchProvider(client(interceptor));

        List<SearchResult> results = provider.search(request("agents flex", 5));

        assertEquals("GET", interceptor.request.method());
        assertEquals(YouSearchProvider.KEYLESS_URL, interceptor.request.url().newBuilder().query(null).build().toString());
        assertEquals("agents flex", interceptor.request.url().queryParameter("query"));
        assertEquals("5", interceptor.request.url().queryParameter("count"));
        assertNull(interceptor.request.header("X-API-Key"));
        assertEquals("youdotcom-integration/agents-flex-agents-flex", interceptor.request.header("User-Agent"));

        assertEquals(2, results.size());
        assertEquals("First", results.get(0).getTitle());
        assertEquals("https://example.com/one", results.get(0).getUrl());
        assertEquals("Summary", results.get(0).getDescription());
        assertEquals("2026-10-01T00:00:00", results.get(0).getMetadata("page_age"));
        assertEquals("Only snippet", results.get(1).getDescription());
    }

    @Test
    public void shouldUseKeyedEndpointWithApiKey() {
        StubInterceptor interceptor = new StubInterceptor(200, BODY);
        YouSearchProvider provider = new YouSearchProvider("ydc-test", client(interceptor));

        provider.search(request("agents flex", 3));

        assertEquals("POST", interceptor.request.method());
        assertEquals(YouSearchProvider.KEYED_URL, interceptor.request.url().toString());
        assertEquals("ydc-test", interceptor.request.header("X-API-Key"));
        JSONObject payload = JSON.parseObject(interceptor.requestBody);
        assertEquals("agents flex", payload.getString("query"));
        assertEquals(3, payload.getIntValue("count"));
    }

    @Test
    public void shouldApplyDomainFiltersAsSiteOperators() {
        SearchRequest request = request("java agents", 10);
        request.setAllowedDomains(Arrays.asList("github.com", "agentsflex.com"));
        request.setBlockedDomains(Arrays.asList("example.net"));
        assertEquals("java agents (site:github.com OR site:agentsflex.com) -site:example.net",
            YouSearchProvider.buildQuery(request));

        request.setAllowedDomains(Arrays.asList("arxiv.org"));
        request.setBlockedDomains(null);
        assertEquals("java agents site:arxiv.org", YouSearchProvider.buildQuery(request));
    }

    @Test
    public void shouldReturnEmptyListWhenNoResults() {
        StubInterceptor interceptor = new StubInterceptor(200, "{\"results\":{\"web\":[]}}");
        assertTrue(new YouSearchProvider(client(interceptor)).search(request("nothing", 5)).isEmpty());
    }

    @Test
    public void shouldExplainAuthError() {
        StubInterceptor interceptor = new StubInterceptor(401, "{\"error\":\"Unauthorized\"}");
        YouSearchProvider provider = new YouSearchProvider("bad", client(interceptor));

        SearchException e = assertThrows(SearchException.class, () -> provider.search(request("q", 5)));

        assertEquals("You.com Search HTTP Error: 401 (invalid or unauthorized API key), body={\"error\":\"Unauthorized\"}",
            e.getMessage());
    }

    @Test
    public void shouldReportRetryAfterOnRateLimit() {
        StubInterceptor interceptor = new StubInterceptor(429, "");
        interceptor.retryAfter = "30";
        YouSearchProvider provider = new YouSearchProvider(client(interceptor));

        SearchException e = assertThrows(SearchException.class, () -> provider.search(request("q", 5)));

        assertTrue(e.getMessage().contains("429 (rate limited, retry after 30s"));
        assertTrue(e.getMessage().contains("keyless tier allows 100 searches a day"));
    }

    @Test
    public void shouldRejectBlankQuery() {
        YouSearchProvider provider = new YouSearchProvider(client(new StubInterceptor(200, BODY)));
        assertThrows(IllegalArgumentException.class, () -> provider.search(request(" ", 5)));
    }

    private static SearchRequest request(String query, int maxResults) {
        SearchRequest request = new SearchRequest();
        request.setQuery(query);
        request.setMaxResults(maxResults);
        return request;
    }

    private static OkHttpClient client(Interceptor interceptor) {
        return new OkHttpClient.Builder().addInterceptor(interceptor).build();
    }

    private static class StubInterceptor implements Interceptor {
        private final int responseCode;
        private final String responseBody;
        private String retryAfter;
        private Request request;
        private String requestBody;

        private StubInterceptor(int responseCode, String responseBody) {
            this.responseCode = responseCode;
            this.responseBody = responseBody;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            request = chain.request();
            if (request.body() != null) {
                Buffer buffer = new Buffer();
                request.body().writeTo(buffer);
                requestBody = buffer.readUtf8();
            }

            Response.Builder builder = new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(responseCode)
                .message("stub")
                .body(ResponseBody.create(responseBody, MediaType.parse("application/json")));
            if (retryAfter != null) {
                builder.header("Retry-After", retryAfter);
            }
            return builder.build();
        }
    }
}

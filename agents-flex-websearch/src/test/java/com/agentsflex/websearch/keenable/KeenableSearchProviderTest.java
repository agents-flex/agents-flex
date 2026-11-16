package com.agentsflex.websearch.keenable;

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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class KeenableSearchProviderTest {

    private static final String RESULTS = "{\"query\":\"agents flex\",\"mode\":\"pro\",\"results\":["
        + "{\"url\":\"https://example.com/one\",\"title\":\"First\",\"snippet\":\"Summary\",\"description\":\"\"},"
        + "{\"url\":\"https://example.com/missing-title\"}]}";

    @Test
    public void shouldUsePublicEndpointWithTitleWithoutKey() {
        StubInterceptor interceptor = new StubInterceptor(200, RESULTS);
        KeenableSearchProvider provider = new KeenableSearchProvider(client(interceptor));
        SearchRequest request = new SearchRequest();
        request.setQuery("agents flex");
        request.setMaxResults(5);

        List<SearchResult> results = provider.search(request);

        assertEquals(1, results.size());
        assertEquals("First", results.get(0).getTitle());
        assertEquals("https://example.com/one", results.get(0).getUrl());
        assertEquals("Summary", results.get(0).getDescription());
        assertEquals("POST", interceptor.request.method());
        assertEquals("https://api.keenable.ai/v1/search/public", interceptor.request.url().toString());
        assertEquals("Agents-Flex", interceptor.request.header("X-Keenable-Title"));
        assertNull(interceptor.request.header("X-API-Key"));

        JSONObject payload = JSON.parseObject(interceptor.requestBody);
        assertEquals("agents flex", payload.getString("query"));
        assertEquals(5, payload.getIntValue("max_results"));
        assertEquals(2, payload.size());
    }

    @Test
    public void shouldUseKeyedEndpointWithKey() {
        StubInterceptor interceptor = new StubInterceptor(200, RESULTS);
        KeenableSearchProvider provider = new KeenableSearchProvider(" kn-test ", client(interceptor));
        SearchRequest request = new SearchRequest();
        request.setQuery("agents flex");

        assertEquals(1, provider.search(request).size());
        assertEquals("https://api.keenable.ai/v1/search", interceptor.request.url().toString());
        assertEquals("kn-test", interceptor.request.header("X-API-Key"));
        assertNull(interceptor.request.header("X-Keenable-Title"));
    }

    @Test
    public void shouldTreatBlankKeyAsKeylessAndSendCustomTitle() {
        StubInterceptor interceptor = new StubInterceptor(200, RESULTS);
        KeenableSearchProvider provider = new KeenableSearchProvider(" ", "My App", client(interceptor));
        SearchRequest request = new SearchRequest();
        request.setQuery("agents flex");

        provider.search(request);

        assertEquals("https://api.keenable.ai/v1/search/public", interceptor.request.url().toString());
        assertEquals("My App", interceptor.request.header("X-Keenable-Title"));
    }

    @Test
    public void shouldMapDomainFilters() {
        SearchRequest one = new SearchRequest();
        one.setQuery(" java records ");
        one.setMaxResults(80);
        one.setAllowedDomains(Arrays.asList("docs.oracle.com", " "));
        one.setBlockedDomains(Arrays.asList("pinterest.com", " example.net "));
        JSONObject payload = KeenableSearchProvider.buildPayload(one);
        assertEquals("java records", payload.getString("query"));
        assertEquals("docs.oracle.com", payload.getString("site"));
        assertEquals(Arrays.asList("pinterest.com", "example.net"), payload.getList("exclude_domains", String.class));
        assertEquals(50, payload.getIntValue("max_results"));

        SearchRequest many = new SearchRequest();
        many.setQuery("java records");
        many.setAllowedDomains(Arrays.asList("docs.oracle.com", "openjdk.org"));
        payload = KeenableSearchProvider.buildPayload(many);
        assertEquals("java records (site:docs.oracle.com OR site:openjdk.org)", payload.getString("query"));
        assertFalse(payload.containsKey("site"));
        assertFalse(payload.containsKey("exclude_domains"));
    }

    @Test
    public void shouldReturnEmptyListWhenThereAreNoResults() {
        StubInterceptor interceptor = new StubInterceptor(200, "{\"results\":[]}");
        KeenableSearchProvider provider = new KeenableSearchProvider(client(interceptor));
        SearchRequest request = new SearchRequest();
        request.setQuery("no results");

        assertTrue(provider.search(request).isEmpty());
    }

    @Test
    public void shouldFailOnHttpError() {
        StubInterceptor interceptor = new StubInterceptor(429, "{\"error\":\"Rate limit exceeded\"}");
        KeenableSearchProvider provider = new KeenableSearchProvider(client(interceptor));
        SearchRequest request = new SearchRequest();
        request.setQuery("keenable");

        SearchException exception = assertThrows(SearchException.class, () -> provider.search(request));

        assertEquals("Keenable Search HTTP Error: 429, body={\"error\":\"Rate limit exceeded\"}", exception.getMessage());
    }

    @Test
    public void shouldRejectBlankQueryAndNullClient() {
        KeenableSearchProvider provider = new KeenableSearchProvider(client(new StubInterceptor(200, "{}")));
        assertThrows(IllegalArgumentException.class, () -> provider.search(new SearchRequest()));
        assertThrows(IllegalArgumentException.class, () -> new KeenableSearchProvider((OkHttpClient) null));
    }

    private static OkHttpClient client(Interceptor interceptor) {
        return new OkHttpClient.Builder().addInterceptor(interceptor).build();
    }

    private static class StubInterceptor implements Interceptor {
        private final int responseCode;
        private final String responseBody;
        private Request request;
        private String requestBody;

        private StubInterceptor(int responseCode, String responseBody) {
            this.responseCode = responseCode;
            this.responseBody = responseBody;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            request = chain.request();
            Buffer buffer = new Buffer();
            request.body().writeTo(buffer);
            requestBody = buffer.readUtf8();

            return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(responseCode)
                .message("stub")
                .body(ResponseBody.create(responseBody, MediaType.parse("application/json")))
                .build();
        }
    }
}

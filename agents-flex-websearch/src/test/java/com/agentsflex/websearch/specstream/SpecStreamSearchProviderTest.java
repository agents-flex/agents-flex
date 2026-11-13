package com.agentsflex.websearch.specstream;

import com.agentsflex.websearch.SearchException;
import com.agentsflex.websearch.SearchRequest;
import com.agentsflex.websearch.SearchResult;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SpecStreamSearchProviderTest {

    @Test
    public void shouldMapRequestAndParseSources() {
        StubInterceptor interceptor = new StubInterceptor(200,
            "{\"data\":{\"request_id\":\"req_1\",\"query\":\"agents flex\",\"kind\":\"fact\",\"sources\":["
                + "{\"url\":\"https://example.com/one\",\"title\":\"First\",\"snippet\":\"Snippet one\",\"published\":\"2026-10-01\"},"
                + "{\"url\":\"https://example.com/two\",\"title\":\"Second\",\"snippet\":\"\",\"excerpt\":\"Excerpt two\"},"
                + "{\"url\":\"https://example.com/no-title\",\"snippet\":\"Skipped\"}],"
                + "\"took_ms\":812,\"price_usd\":0.005}}"
        );
        SpecStreamSearchProvider provider = new SpecStreamSearchProvider("ss-test", client(interceptor));
        SearchRequest request = new SearchRequest();
        request.setQuery("agents flex");
        request.setMaxResults(5);

        List<SearchResult> results = provider.search(request);

        assertEquals(2, results.size());
        assertEquals("First", results.get(0).getTitle());
        assertEquals("https://example.com/one", results.get(0).getUrl());
        assertEquals("Snippet one", results.get(0).getDescription());
        assertEquals("2026-10-01", results.get(0).getMetadata("published"));
        assertEquals("Excerpt two", results.get(1).getDescription());
        assertNull(results.get(1).getMetadata("published"));

        HttpUrl url = interceptor.request.url();
        assertEquals("GET", interceptor.request.method());
        assertEquals("api.specstream.ai", url.host());
        assertEquals("/v1/web-search", url.encodedPath());
        assertEquals("agents flex", url.queryParameter("q"));
        assertEquals("5", url.queryParameter("num"));
        assertNull(url.queryParameter("speed"));
        assertEquals("Bearer ss-test", interceptor.request.header("Authorization"));
    }

    @Test
    public void shouldSendDomainOperatorsAndFastMode() {
        StubInterceptor interceptor = new StubInterceptor(200, "{\"data\":{\"sources\":[]}}");
        SpecStreamSearchProvider provider = new SpecStreamSearchProvider("ss-test", client(interceptor));
        provider.setFast(true);
        SearchRequest request = new SearchRequest();
        request.setQuery(" release notes ");
        request.setMaxResults(500);
        request.setAllowedDomains(Collections.singletonList("agentsflex.com"));
        request.setBlockedDomains(Arrays.asList("spam.example", " "));

        assertTrue(provider.search(request).isEmpty());

        HttpUrl url = interceptor.request.url();
        assertEquals("release notes site:agentsflex.com -site:spam.example", url.queryParameter("q"));
        assertEquals("100", url.queryParameter("num"));
        assertEquals("fast", url.queryParameter("speed"));
    }

    @Test
    public void shouldLeaveSeveralAllowedDomainsToTheToolFilter() {
        SearchRequest request = new SearchRequest();
        request.setQuery("agents flex");
        request.setAllowedDomains(Arrays.asList("a.example", "b.example"));

        assertEquals("agents flex", SpecStreamSearchProvider.buildQuery(request));
    }

    @Test
    public void shouldFailOnHttpError() {
        StubInterceptor interceptor = new StubInterceptor(401,
            "{\"error\":{\"code\":\"unauthorized\",\"message\":\"Invalid API key\"}}");
        SpecStreamSearchProvider provider = new SpecStreamSearchProvider("ss-test", client(interceptor));
        SearchRequest request = new SearchRequest();
        request.setQuery("agents flex");

        SearchException exception = assertThrows(SearchException.class, () -> provider.search(request));

        assertEquals("SpecStream Search HTTP Error: 401, body="
                + "{\"error\":{\"code\":\"unauthorized\",\"message\":\"Invalid API key\"}}",
            exception.getMessage());
    }

    @Test
    public void shouldRejectBlankApiKey() {
        assertThrows(IllegalArgumentException.class, () -> new SpecStreamSearchProvider(" "));
    }

    private static OkHttpClient client(Interceptor interceptor) {
        return new OkHttpClient.Builder().addInterceptor(interceptor).build();
    }

    private static class StubInterceptor implements Interceptor {
        private final int responseCode;
        private final String responseBody;
        private Request request;

        private StubInterceptor(int responseCode, String responseBody) {
            this.responseCode = responseCode;
            this.responseBody = responseBody;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            request = chain.request();

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

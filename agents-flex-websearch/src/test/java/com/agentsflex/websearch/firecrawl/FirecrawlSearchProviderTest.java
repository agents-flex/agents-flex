package com.agentsflex.websearch.firecrawl;

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

public class FirecrawlSearchProviderTest {

    @Test
    public void shouldMapRequestAndParseWebResults() {
        StubInterceptor interceptor = new StubInterceptor(200,
            "{\"success\":true,\"data\":{\"web\":["
                + "{\"url\":\"https://example.com/one\",\"title\":\"First\",\"description\":\"Summary\",\"position\":1},"
                + "{\"url\":\"https://example.com/missing-title\"}]}}"
        );
        FirecrawlSearchProvider provider = new FirecrawlSearchProvider("fc-test", client(interceptor));
        SearchRequest request = new SearchRequest();
        request.setQuery("agents flex");
        request.setMaxResults(5);
        request.setAllowedDomains(Arrays.asList("agentsflex.com", "github.com"));
        request.setBlockedDomains(Arrays.asList("example.net"));

        List<SearchResult> results = provider.search(request);

        assertEquals(1, results.size());
        assertEquals("First", results.get(0).getTitle());
        assertEquals("https://example.com/one", results.get(0).getUrl());
        assertEquals("Summary", results.get(0).getDescription());
        assertEquals("POST", interceptor.request.method());
        assertEquals("https://api.firecrawl.dev/v2/search", interceptor.request.url().toString());
        assertEquals("Bearer fc-test", interceptor.request.header("Authorization"));

        JSONObject payload = JSON.parseObject(interceptor.requestBody);
        assertEquals("agents flex", payload.getString("query"));
        assertEquals(5, payload.getIntValue("limit"));
        assertEquals(Arrays.asList("agentsflex.com", "github.com"), payload.getList("includeDomains", String.class));
        assertEquals(Arrays.asList("example.net"), payload.getList("excludeDomains", String.class));
    }

    @Test
    public void shouldSupportRequestsWithoutApiKey() {
        StubInterceptor interceptor = new StubInterceptor(200, "{\"success\":true,\"data\":{\"web\":[]}}");
        FirecrawlSearchProvider provider = new FirecrawlSearchProvider(client(interceptor));
        SearchRequest request = new SearchRequest();
        request.setQuery("firecrawl");

        provider.search(request);

        assertNull(interceptor.request.header("Authorization"));
    }

    @Test
    public void shouldFailOnHttpError() {
        StubInterceptor interceptor = new StubInterceptor(401, "{\"success\":false,\"error\":\"Unauthorized\"}");
        FirecrawlSearchProvider provider = new FirecrawlSearchProvider("invalid", client(interceptor));
        SearchRequest request = new SearchRequest();
        request.setQuery("firecrawl");

        SearchException exception = assertThrows(SearchException.class, () -> provider.search(request));

        assertEquals("Firecrawl Search HTTP Error: 401, body={\"success\":false,\"error\":\"Unauthorized\"}",
            exception.getMessage());
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

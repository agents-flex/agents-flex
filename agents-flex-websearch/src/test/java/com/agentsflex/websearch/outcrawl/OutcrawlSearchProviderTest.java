package com.agentsflex.websearch.outcrawl;

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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class OutcrawlSearchProviderTest {

    @Test
    public void shouldMapRequestAndParseHits() {
        StubInterceptor interceptor = new StubInterceptor(200,
            "{\"hits\":["
                + "{\"url\":\"https://example.com/one\",\"title\":\"First\",\"snippet\":\"Summary\",\"rank\":1},"
                + "{\"url\":\"https://example.com/missing-title\",\"rank\":2}],"
                + "\"usage\":{\"credits\":\"1\"}}"
        );
        OutcrawlSearchProvider provider = new OutcrawlSearchProvider("oc-test", client(interceptor));
        SearchRequest request = new SearchRequest();
        request.setQuery("agents flex");
        request.setMaxResults(5);
        request.setAllowedDomains(Arrays.asList("agentsflex.com"));
        request.setBlockedDomains(Arrays.asList("example.net"));

        List<SearchResult> results = provider.search(request);

        assertEquals(1, results.size());
        assertEquals("First", results.get(0).getTitle());
        assertEquals("https://example.com/one", results.get(0).getUrl());
        assertEquals("Summary", results.get(0).getDescription());
        assertEquals("POST", interceptor.request.method());
        assertEquals("https://api.outcrawl.ai/v1/search", interceptor.request.url().toString());
        assertEquals("Bearer oc-test", interceptor.request.header("Authorization"));

        JSONObject payload = JSON.parseObject(interceptor.requestBody);
        assertEquals("agents flex", payload.getString("query"));
        assertEquals(5, payload.getIntValue("limit"));
        assertEquals(2, payload.size());
    }

    @Test
    public void shouldReturnEmptyListWhenThereAreNoHits() {
        StubInterceptor interceptor = new StubInterceptor(200, "{\"hits\":[]}");
        OutcrawlSearchProvider provider = new OutcrawlSearchProvider("oc-test", client(interceptor));
        SearchRequest request = new SearchRequest();
        request.setQuery("no results");

        assertTrue(provider.search(request).isEmpty());
    }

    @Test
    public void shouldFailOnHttpError() {
        StubInterceptor interceptor = new StubInterceptor(401, "{\"code\":\"unauthorized\"}");
        OutcrawlSearchProvider provider = new OutcrawlSearchProvider("invalid", client(interceptor));
        SearchRequest request = new SearchRequest();
        request.setQuery("outcrawl");

        SearchException exception = assertThrows(SearchException.class, () -> provider.search(request));

        assertEquals("Outcrawl Search HTTP Error: 401, body={\"code\":\"unauthorized\"}", exception.getMessage());
    }

    @Test
    public void shouldRejectBlankApiKeyAndBlankQuery() {
        assertThrows(IllegalArgumentException.class, () -> new OutcrawlSearchProvider(" "));

        OutcrawlSearchProvider provider = new OutcrawlSearchProvider("oc-test", client(new StubInterceptor(200, "{}")));
        assertThrows(IllegalArgumentException.class, () -> provider.search(new SearchRequest()));
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

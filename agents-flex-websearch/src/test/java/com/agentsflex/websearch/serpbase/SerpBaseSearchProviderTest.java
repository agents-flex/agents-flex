package com.agentsflex.websearch.serpbase;

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
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SerpBaseSearchProviderTest {

    @Test
    public void shouldMapRequestAndParseResults() {
        StubInterceptor interceptor = new StubInterceptor(200,
            "{\"status\":0,\"request_id\":\"req-1\",\"elapsed_ms\":42,\"credits_charged\":1,"
                + "\"organic\":["
                + "{\"position\":1,\"link\":\"https://example.com/one\",\"title\":\"First\",\"snippet\":\"Summary\"},"
                + "{\"position\":2,\"link\":\"https://example.com/missing-title\",\"title\":\"\",\"snippet\":\"Skipped\"}]}"
        );
        SerpBaseSearchProvider provider = new SerpBaseSearchProvider("serpbase-test", client(interceptor));
        SearchRequest request = new SearchRequest();
        request.setQuery("agents flex");
        request.setMaxResults(5);

        List<SearchResult> results = provider.search(request);

        assertEquals(1, results.size());
        assertEquals("First", results.get(0).getTitle());
        assertEquals("https://example.com/one", results.get(0).getUrl());
        assertEquals("Summary", results.get(0).getDescription());

        assertEquals("POST", interceptor.request.method());
        assertEquals("https://api.serpbase.dev/google/search", interceptor.request.url().toString());
        assertEquals("serpbase-test", interceptor.request.header("X-API-Key"));
        // OkHttp derives the Content-Type header from the request body media type.
        assertTrue(interceptor.request.body().contentType().toString().startsWith("application/json"));

        JSONObject payload = JSON.parseObject(interceptor.requestBody);
        assertEquals("agents flex", payload.getString("q"));
        assertEquals(5, payload.getIntValue("num"));
        assertEquals("us", payload.getString("gl"));
        assertEquals("en", payload.getString("hl"));
    }

    @Test
    public void shouldFailOnApiErrorReturnedWithHttp200() {
        // SerpBase answers HTTP 200 with a non-zero business status on failure.
        StubInterceptor interceptor = new StubInterceptor(200, "{\"status\":1001,\"message\":\"invalid api key\"}");
        SerpBaseSearchProvider provider = new SerpBaseSearchProvider("bad-key", client(interceptor));
        SearchRequest request = new SearchRequest();
        request.setQuery("agents flex");

        SearchException exception = assertThrows(SearchException.class, () -> provider.search(request));

        assertEquals("SerpBase Search Error: status=1001, body={\"status\":1001,\"message\":\"invalid api key\"}",
            exception.getMessage());
    }

    @Test
    public void shouldFailOnNoCreditsStatus() {
        StubInterceptor interceptor = new StubInterceptor(200, "{\"status\":1020,\"message\":\"no credits\"}");
        SerpBaseSearchProvider provider = new SerpBaseSearchProvider("serpbase-test", client(interceptor));
        SearchRequest request = new SearchRequest();
        request.setQuery("agents flex");

        SearchException exception = assertThrows(SearchException.class, () -> provider.search(request));

        assertTrue(exception.getMessage().contains("status=1020"));
    }

    @Test
    public void shouldFailOnHttpError() {
        StubInterceptor interceptor = new StubInterceptor(500, "{\"error\":\"internal error\"}");
        SerpBaseSearchProvider provider = new SerpBaseSearchProvider("serpbase-test", client(interceptor));
        SearchRequest request = new SearchRequest();
        request.setQuery("agents flex");

        SearchException exception = assertThrows(SearchException.class, () -> provider.search(request));

        assertEquals("SerpBase Search HTTP Error: 500, body={\"error\":\"internal error\"}",
            exception.getMessage());
    }

    @Test
    public void shouldRejectMissingApiKey() {
        IllegalArgumentException nullKey = assertThrows(IllegalArgumentException.class,
            () -> new SerpBaseSearchProvider(null));
        assertEquals("apiKey must not be empty", nullKey.getMessage());

        IllegalArgumentException blankKey = assertThrows(IllegalArgumentException.class,
            () -> new SerpBaseSearchProvider("  "));
        assertEquals("apiKey must not be empty", blankKey.getMessage());
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

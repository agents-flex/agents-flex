package com.agentsflex.websearch.serply;

import com.agentsflex.websearch.SearchException;
import com.agentsflex.websearch.SearchRequest;
import com.agentsflex.websearch.SearchResult;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class SerplySearchProviderTest {

    @Test
    public void shouldMapRequestAndParseResults() {
        StubInterceptor interceptor = new StubInterceptor(200,
            "{\"results\":["
                + "{\"link\":\"https://example.com/one\",\"title\":\"First\",\"description\":\"Summary\"},"
                + "{\"link\":\"https://example.com/missing-title\",\"title\":\"\",\"description\":\"Skipped\"}]}"
        );
        SerplySearchProvider provider = new SerplySearchProvider("serply-test", client(interceptor));
        SearchRequest request = new SearchRequest();
        request.setQuery("agents flex");
        request.setMaxResults(5);

        List<SearchResult> results = provider.search(request);

        assertEquals(1, results.size());
        assertEquals("First", results.get(0).getTitle());
        assertEquals("https://example.com/one", results.get(0).getUrl());
        assertEquals("Summary", results.get(0).getDescription());
        assertEquals("GET", interceptor.request.method());
        assertEquals("https://api.serply.io/v1/search/?q=agents%20flex&num=5",
            interceptor.request.url().toString());
        assertEquals("serply-test", interceptor.request.header("X-Api-Key"));
        assertEquals("agents-flex", interceptor.request.header("User-Agent"));
    }

    @Test
    public void shouldFailOnHttpError() {
        StubInterceptor interceptor = new StubInterceptor(401, "{\"error\":\"invalid key\"}");
        SerplySearchProvider provider = new SerplySearchProvider("serply-test", client(interceptor));
        SearchRequest request = new SearchRequest();
        request.setQuery("agents flex");

        SearchException exception = assertThrows(SearchException.class, () -> provider.search(request));

        assertEquals("Serply HTTP Error: 401, body={\"error\":\"invalid key\"}", exception.getMessage());
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

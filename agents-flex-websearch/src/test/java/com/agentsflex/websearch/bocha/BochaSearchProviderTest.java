package com.agentsflex.websearch.bocha;

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

public class BochaSearchProviderTest {

    @Test
    public void shouldMapRequestAndParseResults() {
        StubInterceptor interceptor = new StubInterceptor(200,
            "{\"code\":200,\"data\":{\"webPages\":{\"value\":["
                + "{\"name\":\"First\",\"url\":\"https://example.com/one\",\"snippet\":\"Snippet\",\"summary\":\"Summary\"},"
                + "{\"name\":\"\",\"url\":\"https://example.com/no-title\",\"snippet\":\"Skipped\"}]}}}"
        );
        BochaSearchProvider provider = new BochaSearchProvider("bocha-test", client(interceptor));
        SearchRequest request = new SearchRequest();
        request.setQuery("agents flex");
        request.setMaxResults(5);

        List<SearchResult> results = provider.search(request);

        assertEquals(1, results.size());
        assertEquals("First", results.get(0).getTitle());
        assertEquals("https://example.com/one", results.get(0).getUrl());
        assertEquals("Summary", results.get(0).getDescription());
        assertEquals("POST", interceptor.request.method());
        assertEquals("https://api.bocha.cn/v1/web-search", interceptor.request.url().toString());
        assertEquals("Bearer bocha-test", interceptor.request.header("Authorization"));
    }

    @Test
    public void shouldFailOnHttpError() {
        StubInterceptor interceptor = new StubInterceptor(401, "{\"code\":401,\"msg\":\"invalid api key\"}");
        BochaSearchProvider provider = new BochaSearchProvider("bocha-test", client(interceptor));
        SearchRequest request = new SearchRequest();
        request.setQuery("agents flex");

        SearchException exception = assertThrows(SearchException.class, () -> provider.search(request));

        assertEquals("Bocha Search HTTP Error: 401, body={\"code\":401,\"msg\":\"invalid api key\"}",
            exception.getMessage());
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

package com.agentsflex.websearch.baidu;

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

public class BaiduQianfanSearchProviderTest {

    @Test
    public void shouldMapRequestAndParseResults() {
        StubInterceptor interceptor = new StubInterceptor(200,
            "{\"references\":["
                + "{\"title\":\"First\",\"url\":\"https://example.com/one\",\"content\":\"Content\","
                + "\"date\":\"2026-11-01\",\"website\":\"example.com\"},"
                + "{\"title\":\"\",\"url\":\"https://example.com/no-title\",\"content\":\"Skipped\"}]}"
        );
        BaiduQianfanSearchProvider provider = new BaiduQianfanSearchProvider("baidu-test", client(interceptor));
        SearchRequest request = new SearchRequest();
        request.setQuery("agents flex");

        List<SearchResult> results = provider.search(request);

        assertEquals(1, results.size());
        assertEquals("First", results.get(0).getTitle());
        assertEquals("https://example.com/one", results.get(0).getUrl());
        assertEquals("Content", results.get(0).getDescription());
        assertEquals("2026-11-01", results.get(0).getFrontMatter().get("date"));
        assertEquals("example.com", results.get(0).getFrontMatter().get("website"));
        assertEquals("POST", interceptor.request.method());
        assertEquals("https://qianfan.baidubce.com/v2/ai_search/web_search",
            interceptor.request.url().toString());
        assertEquals("Bearer baidu-test", interceptor.request.header("Authorization"));
    }

    @Test
    public void shouldFailOnHttpError() {
        StubInterceptor interceptor = new StubInterceptor(403, "{\"error_code\":216005,\"error_msg\":\"no permission\"}");
        BaiduQianfanSearchProvider provider = new BaiduQianfanSearchProvider("baidu-test", client(interceptor));
        SearchRequest request = new SearchRequest();
        request.setQuery("agents flex");

        SearchException exception = assertThrows(SearchException.class, () -> provider.search(request));

        assertEquals("Baidu Qianfan Search HTTP Error: 403, "
            + "body={\"error_code\":216005,\"error_msg\":\"no permission\"}", exception.getMessage());
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

package com.agentsflex.core.test.util;

import com.agentsflex.core.model.client.AgentsFlexHttpClient;
import com.agentsflex.core.util.Retryer;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import okio.Okio;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

public class AgentsFlexHttpClientTest {

    @Test
    public void shouldReturnResponseBody() {
        AgentsFlexHttpClient client = clientReturning(200, "ok");

        assertEquals("ok", client.get("https://example.test/resource"));
    }

    @Test
    public void shouldPropagateIoFailure() {
        OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .addInterceptor(chain -> {
                throw new IOException("connection failed");
            })
            .build();

        try {
            new AgentsFlexHttpClient(okHttpClient).get("https://example.test/resource");
            fail("Expected UncheckedIOException");
        } catch (UncheckedIOException expected) {
            assertEquals("connection failed", expected.getCause().getMessage());
        }
    }

    @Test
    public void shouldAllowRetryerToRetryIoFailure() {
        AtomicInteger attempts = new AtomicInteger();
        OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .addInterceptor(chain -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new IOException("temporary failure");
                }
                return response(chain.request(), 200, "ok");
            })
            .build();

        String result = Retryer.retry(
            () -> new AgentsFlexHttpClient(okHttpClient).get("https://example.test/resource"), 1, 0);

        assertEquals("ok", result);
        assertEquals(2, attempts.get());
    }

    @Test
    public void shouldReturnOpenResponseToCaller() throws IOException {
        AgentsFlexHttpClient client = clientReturning(200, "response body");

        try (Response response = client.getResponse(
            "https://example.test/resource", Collections.emptyMap())) {
            assertNotNull(response.body());
            assertEquals("response body", response.body().string());
        }
    }

    @Test
    public void shouldReturnStatusCodeWithoutExposingResponse() {
        AgentsFlexHttpClient client = clientReturning(404, "not found");

        assertEquals(404, client.getStatusCode(
            "https://example.test/resource", Collections.emptyMap()));
    }

    @Test
    public void shouldNotUseAvailableAsInputStreamLength() throws IOException {
        InputStream stream = new ByteArrayInputStream(new byte[]{1, 2, 3});
        AgentsFlexHttpClient.InputStreamRequestBody body = new AgentsFlexHttpClient.InputStreamRequestBody(
            MediaType.parse("application/octet-stream"), stream);

        assertEquals(-1, body.contentLength());
    }

    private static AgentsFlexHttpClient clientReturning(int statusCode, String body) {
        Interceptor interceptor = chain -> response(chain.request(), statusCode, body);
        return new AgentsFlexHttpClient(new OkHttpClient.Builder().addInterceptor(interceptor).build());
    }

    private static Response response(okhttp3.Request request, int statusCode, String body) {
        return new Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(statusCode)
            .message("test response")
            .body(new CloseAwareResponseBody(body))
            .build();
    }

    private static class CloseAwareResponseBody extends ResponseBody {
        private final BufferedSource source;

        private CloseAwareResponseBody(String content) {
            this.source = Okio.buffer(Okio.source(new ByteArrayInputStream(
                content.getBytes(StandardCharsets.UTF_8))));
        }

        @Override
        public MediaType contentType() {
            return MediaType.parse("text/plain; charset=utf-8");
        }

        @Override
        public long contentLength() {
            return -1;
        }

        @Override
        public BufferedSource source() {
            return source;
        }
    }
}

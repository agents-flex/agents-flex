/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.agentsflex.core.model.client;

import com.agentsflex.core.observability.Observability;
import com.agentsflex.core.util.IOUtil;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import okhttp3.*;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import okio.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public class AgentsFlexHttpClient {
    private static final Logger LOG = LoggerFactory.getLogger(AgentsFlexHttpClient.class);
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final AgentsFlexHttpClient INSTANCE = new AgentsFlexHttpClient();

    private static final class Instruments {
        private static final Tracer TRACER = Observability.getTracer();
        private static final Meter METER = Observability.getMeter();
        private static final LongCounter REQUEST_COUNT = METER.counterBuilder("http.client.request.count")
            .setDescription("Total number of HTTP client requests")
            .build();
        private static final DoubleHistogram LATENCY = METER.histogramBuilder("http.client.request.duration")
            .setDescription("HTTP client request duration in seconds")
            .setUnit("s")
            .build();
        private static final LongCounter ERROR_COUNT = METER.counterBuilder("http.client.request.error.count")
            .setDescription("Total number of HTTP client request errors")
            .build();
    }

    private final OkHttpClient okHttpClient;

    public static AgentsFlexHttpClient getDefault() {
        return INSTANCE;
    }

    public AgentsFlexHttpClient() {
        this(OkHttpClientUtil.buildDefaultClient());
    }

    public AgentsFlexHttpClient(OkHttpClient okHttpClient) {
        this.okHttpClient = Objects.requireNonNull(okHttpClient, "okHttpClient must not be null");
    }




    public String get(String url) {
        return executeObserved(url, "GET", null, null,
            this::executeAndReadString);
    }

    public byte[] getBytes(String url) {
        return executeObserved(url, "GET", null, null,
            this::executeAndReadBytes);
    }

    public String get(String url, Map<String, String> headers) {
        return executeObserved(url, "GET", headers, null,
            this::executeAndReadString);
    }

    /**
     * Executes a GET request and transfers ownership of the open response to the caller.
     * The caller must close the returned response.
     */
    public Response getResponse(String url, Map<String, String> headers) {
        return openObservedResponse(url, "GET", headers, null);
    }

    /**
     * Executes a GET request and closes the response after reading its status code.
     */
    public int getStatusCode(String url, Map<String, String> headers) {
        return executeObserved(url, "GET", headers, null, (u, m, h, p, s, observation) -> {
            try {
                try (Response response = executeRequest(u, m, h, p, observation)) {
                    return response.code();
                }
            } catch (IOException ioe) {
                throw recordAndWrapIOException("HTTP getStatusCode failed: " + u, ioe, s);
            }
        });
    }

    public String post(String url, Map<String, String> headers, String payload) {
        return executeObserved(url, "POST", headers, payload,
            this::executeAndReadString);
    }

    public byte[] postBytes(String url, Map<String, String> headers, String payload) {
        return executeObserved(url, "POST", headers, payload,
            this::executeAndReadBytes);
    }

    public String put(String url, Map<String, String> headers, String payload) {
        return executeObserved(url, "PUT", headers, payload,
            this::executeAndReadString);
    }

    public String delete(String url, Map<String, String> headers, String payload) {
        return executeObserved(url, "DELETE", headers, payload,
            this::executeAndReadString);
    }

    public String multipartString(String url, Map<String, String> headers, Map<String, Object> payload) {
        return executeObserved(url, "POST", headers, payload, (u, m, h, p, s, observation) -> {
            //noinspection unchecked
            try (Response response = executeMultipartRequest(u, h, (Map<String, Object>) p, observation);
                 ResponseBody body = response.body()) {
                if (body != null) {
                    return body.string();
                }
            } catch (IOException ioe) {
                throw recordAndWrapIOException("HTTP multipartString failed: " + u, ioe, s);
            }
            return null;
        });
    }

    public byte[] multipartBytes(String url, Map<String, String> headers, Map<String, Object> payload) {
        return executeObserved(url, "POST", headers, payload, (u, m, h, p, s, observation) -> {
            //noinspection unchecked
            try (Response response = executeMultipartRequest(u, h, (Map<String, Object>) p, observation);
                 ResponseBody body = response.body()) {
                if (body != null) {
                    return body.bytes();
                }
            } catch (IOException ioe) {
                throw recordAndWrapIOException("HTTP multipartBytes failed: " + u, ioe, s);
            }
            return null;
        });
    }

    // ===== Internal execution methods =====

    public String executeString(String url, String method, Map<String, String> headers, Object payload, Span span) {
        return executeAndReadString(url, method, headers, payload, span, null);
    }

    private String executeAndReadString(String url, String method, Map<String, String> headers, Object payload,
                                        Span span, RequestObservation observation) {
        try (Response response = executeRequest(url, method, headers, payload, observation);
             ResponseBody body = response.body()) {
            if (body != null) {
                return body.string();
            }
        } catch (IOException ioe) {
            throw recordAndWrapIOException("HTTP executeString failed: " + url, ioe, span);
        }
        return null;
    }

    public byte[] executeBytes(String url, String method, Map<String, String> headers, Object payload, Span span) {
        return executeAndReadBytes(url, method, headers, payload, span, null);
    }

    private byte[] executeAndReadBytes(String url, String method, Map<String, String> headers, Object payload,
                                       Span span, RequestObservation observation) {
        try (Response response = executeRequest(url, method, headers, payload, observation);
             ResponseBody body = response.body()) {
            if (body != null) {
                return body.bytes();
            }
        } catch (IOException ioe) {
            throw recordAndWrapIOException("HTTP executeBytes failed: " + url, ioe, span);
        }
        return null;
    }

    public Response executeResponse(
        String url, String method, Map<String, String> headers, Object payload, Span span) {
        return openResponse(url, method, headers, payload, span, null);
    }

    private Response openResponse(String url, String method, Map<String, String> headers, Object payload,
                                  Span span, RequestObservation observation) {
        try {
            return executeRequest(url, method, headers, payload, observation);
        } catch (IOException ioe) {
            throw recordAndWrapIOException("HTTP executeResponse failed: " + url, ioe, span);
        }
    }

    private Response executeRequest(String url, String method, Map<String, String> headers, Object payload,
                                    RequestObservation observation) throws IOException {
        Request.Builder builder = new Request.Builder().url(url);
        if (headers != null && !headers.isEmpty()) {
            headers.forEach((key, value) -> {
                if (key != null && value != null) {
                    builder.addHeader(key, value);
                }
            });
        }

        Request request;
        if ("GET".equalsIgnoreCase(method)) {
            request = builder.build();
        } else {
            RequestBody body = RequestBody.create(payload == null ? "" : payload.toString(), JSON_TYPE);
            request = builder.method(method, body).build();
        }

        request = propagateTraceContext(request.newBuilder()).build();

        Response response = okHttpClient.newCall(request).execute();

        // Inject status code into current span
        recordResponseStatus(response, observation);
        return response;
    }

    public Response multipart(String url, Map<String, String> headers, Map<String, Object> payload) throws IOException {
        return executeMultipartRequest(url, headers, payload, null);
    }

    private Response executeMultipartRequest(String url, Map<String, String> headers, Map<String, Object> payload,
                                             RequestObservation observation) throws IOException {
        Request.Builder builder = new Request.Builder().url(url);
        if (headers != null && !headers.isEmpty()) {
            headers.forEach((key, value) -> {
                if (key != null && value != null) {
                    builder.addHeader(key, value);
                }
            });
        }

        MultipartBody.Builder mbBuilder = new MultipartBody.Builder().setType(MultipartBody.FORM);
        payload.forEach((key, value) -> {
            if (value instanceof File) {
                File file = (File) value;
                RequestBody body = RequestBody.create(file, MediaType.parse("application/octet-stream"));
                mbBuilder.addFormDataPart(key, file.getName(), body);
            } else if (value instanceof InputStream) {
                RequestBody body = new InputStreamRequestBody(MediaType.parse("application/octet-stream"), (InputStream) value);
                mbBuilder.addFormDataPart(key, key, body);
            } else if (value instanceof byte[]) {
                mbBuilder.addFormDataPart(key, key, RequestBody.create((byte[]) value));
            } else {
                mbBuilder.addFormDataPart(key, String.valueOf(value));
            }
        });

        MultipartBody multipartBody = mbBuilder.build();
        Request request = propagateTraceContext(builder.post(multipartBody)).build();
        Response response = okHttpClient.newCall(request).execute();

        // Record the status code for tracing and metrics.
        recordResponseStatus(response, observation);
        return response;
    }

    // ===== Shared helper for span status code injection =====

    private void recordResponseStatus(Response response, RequestObservation observation) {
        Span currentSpan = Span.current();
        int statusCode = response.code();
        if (observation != null) {
            observation.statusCode = statusCode;
        }
        if (currentSpan != null && currentSpan != Span.getInvalid()) {
            currentSpan.setAttribute("http.status_code", statusCode);
            currentSpan.setAttribute("http.response.status_code", statusCode);
            if (statusCode >= 400) {
                currentSpan.setStatus(StatusCode.ERROR, "HTTP " + statusCode);
            }
        }
    }

    // ===== Observability wrapper =====
    @FunctionalInterface
    private interface HttpRequestOperation<T> {
        T execute(String url, String method, Map<String, String> headers, Object payload,
                  Span span, RequestObservation observation) throws Exception;
    }

    private <T> T executeObserved(String url, String method, Map<String, String> headers, Object payload,
                                  HttpRequestOperation<T> operation) {
        Objects.requireNonNull(url, "url must not be null");
        if (!Observability.isEnabled()) {
            try {
                return operation.execute(url, method, headers, payload, Span.getInvalid(), new RequestObservation());
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("HTTP request failed", e);
            }
        }

        String host = extractServerAddress(url);
        Span span = createHttpSpan(url, method, host);

        long startTime = System.nanoTime();
        boolean success = false;
        RequestObservation observation = new RequestObservation();

        try (Scope scope = span.makeCurrent()) {
            T result = operation.execute(url, method, headers, payload, span, observation);
            success = observation.statusCode == null || observation.statusCode < 400;
            return result;
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException("HTTP request failed", e);
        } finally {
            span.end();
            recordHttpMetrics(method, host, success, observation.statusCode, startTime);
        }
    }

    private Response openObservedResponse(String url, String method, Map<String, String> headers, Object payload) {
        Objects.requireNonNull(url, "url must not be null");
        if (!Observability.isEnabled()) {
            return openResponse(url, method, headers, payload, Span.getInvalid(), new RequestObservation());
        }

        String host = extractServerAddress(url);
        Span span = createHttpSpan(url, method, host);
        long startTime = System.nanoTime();
        RequestObservation observation = new RequestObservation();
        AtomicBoolean completed = new AtomicBoolean(false);

        try (Scope ignored = span.makeCurrent()) {
            Response response = openResponse(url, method, headers, payload, span, observation);
            boolean statusSuccess = observation.statusCode == null || observation.statusCode < 400;
            ResponseBody body = response.body();
            if (body == null) {
                completeHttpObservation(completed, span, method, host, statusSuccess,
                    observation.statusCode, startTime, null);
                return response;
            }
            ResponseBody observedBody = new ObservedResponseBody(body, span, error ->
                completeHttpObservation(completed, span, method, host,
                    statusSuccess && error == null, observation.statusCode, startTime, error));
            return response.newBuilder().body(observedBody).build();
        } catch (RuntimeException | Error error) {
            completeHttpObservation(completed, span, method, host, false,
                observation.statusCode, startTime, error);
            throw error;
        }
    }

    private static Span createHttpSpan(String url, String method, String host) {
        String safeUrl = sanitizeUrl(url);
        return Instruments.TRACER.spanBuilder("http.client.request")
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute("http.method", method)
            .setAttribute("http.request.method", method)
            .setAttribute("http.url", safeUrl)
            .setAttribute("url.full", safeUrl)
            .setAttribute("server.address", host)
            .startSpan();
    }

    private static void completeHttpObservation(AtomicBoolean completed, Span span, String method,
                                                String host, boolean success, Integer statusCode,
                                                long startTime, Throwable error) {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        if (error != null) {
            span.setStatus(StatusCode.ERROR, error.getMessage());
            span.recordException(error);
        }
        span.end();
        recordHttpMetrics(method, host, success, statusCode, startTime);
    }

    private static void recordHttpMetrics(String method, String host, boolean success,
                                          Integer statusCode, long startTime) {
        double latency = (System.nanoTime() - startTime) / 1_000_000_000.0;
        AttributesBuilder attributesBuilder = Attributes.builder()
            .put(AttributeKey.stringKey("http.method"), method)
            .put(AttributeKey.stringKey("server.address"), host)
            .put(AttributeKey.booleanKey("http.success"), success);
        if (statusCode != null) {
            attributesBuilder.put(AttributeKey.longKey("http.response.status_code"), statusCode.longValue());
        }
        Attributes attrs = attributesBuilder.build();
        Instruments.REQUEST_COUNT.add(1, attrs);
        Instruments.LATENCY.record(latency, attrs);
        if (!success) {
            Instruments.ERROR_COUNT.add(1, attrs);
        }
    }

    // ===== Utility =====
    private static String extractServerAddress(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host == null) {
                return "unknown";
            }
            int port = uri.getPort();
            if (port != -1) {
                return host + ":" + port;
            }
            return host;
        } catch (URISyntaxException e) {
            return "unknown";
        }
    }

    private static String sanitizeUrl(String url) {
        try {
            URI uri = new URI(url);
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();
        } catch (URISyntaxException e) {
            return "unknown";
        }
    }

    private static UncheckedIOException recordAndWrapIOException(String message, IOException cause, Span span) {
        LOG.error(message, cause);
        if (span != null) {
            span.setStatus(StatusCode.ERROR, cause.getMessage());
            span.recordException(cause);
        }
        return new UncheckedIOException(message, cause);
    }

    private static Request.Builder propagateTraceContext(Request.Builder requestBuilder) {
        Observability.getOpenTelemetry().getPropagators().getTextMapPropagator().inject(
            Context.current(), requestBuilder, (carrier, key, value) -> carrier.header(key, value));
        return requestBuilder;
    }

    private static class RequestObservation {
        private Integer statusCode;
    }

    @FunctionalInterface
    private interface ResponseCompletion {
        void complete(Throwable error);
    }

    private static class ObservedResponseBody extends ResponseBody {
        private final ResponseBody delegate;
        private final Span span;
        private final ResponseCompletion completion;
        private BufferedSource source;

        private ObservedResponseBody(ResponseBody delegate, Span span, ResponseCompletion completion) {
            this.delegate = delegate;
            this.span = span;
            this.completion = completion;
        }

        @Override
        public MediaType contentType() {
            return delegate.contentType();
        }

        @Override
        public long contentLength() {
            return delegate.contentLength();
        }

        @Override
        public BufferedSource source() {
            if (source == null) {
                Source forwardingSource = new ForwardingSource(delegate.source()) {
                    @Override
                    public long read(Buffer sink, long byteCount) throws IOException {
                        try (Scope ignored = span.makeCurrent()) {
                            long read = super.read(sink, byteCount);
                            if (read == -1) {
                                completion.complete(null);
                            }
                            return read;
                        } catch (IOException error) {
                            completion.complete(error);
                            throw error;
                        }
                    }

                    @Override
                    public void close() throws IOException {
                        try (Scope ignored = span.makeCurrent()) {
                            super.close();
                            completion.complete(null);
                        } catch (IOException error) {
                            completion.complete(error);
                            throw error;
                        }
                    }
                };
                source = Okio.buffer(forwardingSource);
            }
            return source;
        }
    }

    // ===== Inner class =====

    public static class InputStreamRequestBody extends RequestBody {
        private final InputStream inputStream;
        private final MediaType contentType;

        public InputStreamRequestBody(MediaType contentType, InputStream inputStream) {
            if (inputStream == null) throw new NullPointerException("inputStream == null");
            this.contentType = contentType;
            this.inputStream = inputStream;
        }

        @Override
        public MediaType contentType() {
            return contentType;
        }

        @Override
        public long contentLength() {
            return -1;
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            IOUtil.copy(inputStream, sink);
        }
    }
}

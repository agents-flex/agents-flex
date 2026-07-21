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
import com.agentsflex.core.observability.ObservabilityRuntime;
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
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agents-Flex 内部统一使用的 HTTP 客户端，同时负责创建 HTTP CLIENT Span、记录指标并传播 Trace Context。
 *
 * <p>可观测实现会在请求开始时锁定当前 {@link ObservabilityRuntime} 对应的 instrument，确保即使响应体稍后
 * 才被消费，Span 和 Metrics 仍发送到发起请求时选择的 Route。</p>
 */
public class AgentsFlexHttpClient {
    /** HTTP 执行或响应体读取异常使用的日志记录器。 */
    private static final Logger LOG = LoggerFactory.getLogger(AgentsFlexHttpClient.class);

    /** 非 GET 请求默认使用的 JSON 请求体媒体类型。 */
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    /** 使用默认 OkHttp 配置创建的共享客户端实例。 */
    private static final AgentsFlexHttpClient INSTANCE = new AgentsFlexHttpClient();

    /**
     * runtime 到 HTTP 埋点 instrument 的弱键缓存。
     * 一个 runtime 对应一组 instrument，弱键避免动态下线的 Route 被静态缓存永久持有。
     */
    private static final Map<ObservabilityRuntime, Instruments> INSTRUMENTS = new WeakHashMap<>();

    /** 某个 ObservabilityRuntime 专属的一组 HTTP Tracer 和 Metrics instrument。 */
    private static final class Instruments {
        /** 创建 HTTP CLIENT Span 的 Tracer。 */
        private final Tracer tracer;

        /** 记录全部 HTTP 请求次数的 Counter。 */
        private final LongCounter requestCount;

        /** 记录包含响应体消费时间在内的 HTTP 请求耗时 Histogram，单位为秒。 */
        private final DoubleHistogram latency;

        /** 只记录网络异常或失败状态请求次数的 Counter。 */
        private final LongCounter errorCount;

        private Instruments(ObservabilityRuntime runtime) {
            this.tracer = runtime.getTracer();
            Meter meter = runtime.getMeter();
            this.requestCount = meter.counterBuilder("http.client.request.count")
                .setDescription("Total number of HTTP client requests")
                .build();
            this.latency = meter.histogramBuilder("http.client.request.duration")
                .setDescription("HTTP client request duration in seconds")
                .setUnit("s")
                .build();
            this.errorCount = meter.counterBuilder("http.client.request.error.count")
                .setDescription("Total number of HTTP client request errors")
                .build();
        }
    }

    /** 实际执行网络请求的 OkHttpClient，由构造函数注入或使用框架默认配置创建。 */
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

        // 使用当前 runtime 的 Propagator 注入 traceparent 等请求头，保证跨服务 Trace 连续。
        request = propagateTraceContext(request.newBuilder()).build();

        Response response = okHttpClient.newCall(request).execute();

        // 同时保存到 RequestObservation，供 Span 结束后的 Metrics 判断成功状态。
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

        // 同时记录到 Span 和请求观察状态，供后续 Metrics 使用。
        recordResponseStatus(response, observation);
        return response;
    }

    // ===== HTTP 状态记录 =====

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

    // ===== 可观测执行包装 =====
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
        Instruments instruments = instruments();
        Span span = createHttpSpan(instruments, url, method, host);

        long startTime = System.nanoTime();
        boolean success = false;
        RequestObservation observation = new RequestObservation();

        // Scope 覆盖网络调用全过程，使传播器能从 Context.current() 取得当前 CLIENT Span。
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
            recordHttpMetrics(instruments, method, host, success, observation.statusCode, startTime);
        }
    }

    private Response openObservedResponse(String url, String method, Map<String, String> headers, Object payload) {
        Objects.requireNonNull(url, "url must not be null");
        if (!Observability.isEnabled()) {
            return openResponse(url, method, headers, payload, Span.getInvalid(), new RequestObservation());
        }

        String host = extractServerAddress(url);
        Instruments instruments = instruments();
        Span span = createHttpSpan(instruments, url, method, host);
        long startTime = System.nanoTime();
        RequestObservation observation = new RequestObservation();
        // 开放响应的生命周期由调用方控制，关闭、读到 EOF 或读取失败都可能触发完成逻辑，必须保证只结束一次。
        AtomicBoolean completed = new AtomicBoolean(false);

        try (Scope ignored = span.makeCurrent()) {
            Response response = openResponse(url, method, headers, payload, span, observation);
            boolean statusSuccess = observation.statusCode == null || observation.statusCode < 400;
            ResponseBody body = response.body();
            if (body == null) {
                completeHttpObservation(instruments, completed, span, method, host, statusSuccess,
                    observation.statusCode, startTime, null);
                return response;
            }
            // Span 不能在收到响应头时立即结束，因为响应体下载仍是请求耗时的一部分。
            ResponseBody observedBody = new ObservedResponseBody(body, span, error ->
                completeHttpObservation(instruments, completed, span, method, host,
                    statusSuccess && error == null, observation.statusCode, startTime, error));
            return response.newBuilder().body(observedBody).build();
        } catch (RuntimeException | Error error) {
            completeHttpObservation(instruments, completed, span, method, host, false,
                observation.statusCode, startTime, error);
            throw error;
        }
    }

    private static Span createHttpSpan(Instruments instruments, String url, String method, String host) {
        String safeUrl = sanitizeUrl(url);
        Span span = instruments.tracer.spanBuilder("http.client.request")
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute("http.method", method)
            .setAttribute("http.request.method", method)
            .setAttribute("http.url", safeUrl)
            .setAttribute("url.full", safeUrl)
            .setAttribute("server.address", host)
            .startSpan();
        Observability.enrichSpan(span);
        return span;
    }

    private static void completeHttpObservation(Instruments instruments, AtomicBoolean completed,
                                                Span span, String method,
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
        recordHttpMetrics(instruments, method, host, success, statusCode, startTime);
    }

    private static void recordHttpMetrics(Instruments instruments, String method, String host, boolean success,
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
        instruments.requestCount.add(1, attrs);
        instruments.latency.record(latency, attrs);
        if (!success) {
            instruments.errorCount.add(1, attrs);
        }
    }

    private static Instruments instruments() {
        ObservabilityRuntime runtime = Observability.currentRuntime();
        // WeakHashMap 非线程安全，instrument 的查找与首次创建必须原子化。
        synchronized (INSTRUMENTS) {
            Instruments instruments = INSTRUMENTS.get(runtime);
            if (instruments == null) {
                instruments = new Instruments(runtime);
                INSTRUMENTS.put(runtime, instruments);
            }
            return instruments;
        }
    }

    // ===== 辅助方法 =====
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
        // URL 的 user-info、query 和 fragment 可能包含凭证或业务参数，只保留定位服务所需的安全部分。
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
        // 必须从当前 runtime 取得 Propagator，不能固定使用全局 SDK，否则执行级 Route 的传播配置会失效。
        Observability.getOpenTelemetry().getPropagators().getTextMapPropagator().inject(
            Context.current(), requestBuilder, (carrier, key, value) -> carrier.header(key, value));
        return requestBuilder;
    }

    private static class RequestObservation {
        /** 收到的 HTTP 状态码；请求在收到响应前失败时保持为 null。 */
        private Integer statusCode;
    }

    @FunctionalInterface
    private interface ResponseCompletion {
        void complete(Throwable error);
    }

    /**
     * 用装饰器跟踪开放响应体的真实消费边界。读到 EOF、读取异常或显式 close 都会通知完成回调，外层 CAS
     * 负责去重。这里在读取期间恢复 Span，便于 OkHttp/下游代码读取 {@link Span#current()}。
     */
    private static class ObservedResponseBody extends ResponseBody {
        /** 原始响应体，所有媒体类型、长度和读取操作都委托给它。 */
        private final ResponseBody delegate;

        /** 发起请求时创建的 CLIENT Span，在响应体读取和关闭期间恢复为当前 Span。 */
        private final Span span;

        /** 响应体到达 EOF、关闭或读取失败时调用的完成通知。 */
        private final ResponseCompletion completion;

        /** 对原始 source 的单例包装，确保多次调用 source() 不会重复创建完成监听器。 */
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
        /** 上传内容来源；该类不预读流，也不根据 available() 推断长度。 */
        private final InputStream inputStream;

        /** 上传流对应的 HTTP Content-Type。 */
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

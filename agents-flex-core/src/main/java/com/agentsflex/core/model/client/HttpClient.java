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
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import okhttp3.*;
import okio.BufferedSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

public class HttpClient {
    private static final Logger LOG = LoggerFactory.getLogger(HttpClient.class);
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    // ===== Observability Components =====
    private static final Tracer TRACER = Observability.getTracer();
    private static final Meter METER = Observability.getMeter();

    private static final LongCounter HTTP_REQUEST_COUNT = METER.counterBuilder("http.client.request.count")
        .setDescription("Total number of HTTP client requests")
        .build();

    private static final DoubleHistogram HTTP_LATENCY_HISTOGRAM = METER.histogramBuilder("http.client.request.duration")
        .setDescription("HTTP client request duration in seconds")
        .setUnit("s")
        .build();

    private static final LongCounter HTTP_ERROR_COUNT = METER.counterBuilder("http.client.request.error.count")
        .setDescription("Total number of HTTP client request errors")
        .build();

    private final OkHttpClient okHttpClient;

    public HttpClient() {
        this(OkHttpClientUtil.buildDefaultClient());
    }

    public HttpClient(OkHttpClient okHttpClient) {
        this.okHttpClient = okHttpClient;
    }


    public String get(String url) {
        return tracedCall(url, "GET", null, null, this::executeString);
    }

    public byte[] getBytes(String url) {
        return tracedCall(url, "GET", null, null, this::executeBytes);
    }

    public String get(String url, Map<String, String> headers) {
        return tracedCall(url, "GET", headers, null, this::executeString);
    }

    public String post(String url, Map<String, String> headers, String payload) {
        return tracedCall(url, "POST", headers, payload, this::executeString);
    }

    public byte[] postBytes(String url, Map<String, String> headers, String payload) {
        return tracedCall(url, "POST", headers, payload, this::executeBytes);
    }

    public String put(String url, Map<String, String> headers, String payload) {
        return tracedCall(url, "PUT", headers, payload, this::executeString);
    }

    public String delete(String url, Map<String, String> headers, String payload) {
        return tracedCall(url, "DELETE", headers, payload, this::executeString);
    }

    public String multipartString(String url, Map<String, String> headers, Map<String, Object> payload) {
        return tracedCall(url, "POST", headers, payload, (u, m, h, p, s) -> {
            //noinspection unchecked
            try (Response response = multipart(u, h, (Map<String, Object>) p);
                 ResponseBody body = response.body()) {
                if (body != null) {
                    return body.string();
                }
            } catch (IOException ioe) {
                LOG.error("HTTP multipartString failed: " + u, ioe);
                s.setStatus(StatusCode.ERROR, ioe.getMessage());
                s.recordException(ioe);
            } catch (Exception e) {
                LOG.error(e.toString(), e);
                throw e;
            }
            return null;
        });
    }

    public byte[] multipartBytes(String url, Map<String, String> headers, Map<String, Object> payload) {
        return tracedCall(url, "POST", headers, payload, (u, m, h, p, s) -> {
            //noinspection unchecked
            try (Response response = multipart(u, h, (Map<String, Object>) p);
                 ResponseBody body = response.body()) {
                if (body != null) {
                    return body.bytes();
                }
            } catch (IOException ioe) {
                LOG.error("HTTP multipartBytes failed: " + u, ioe);
                s.setStatus(StatusCode.ERROR, ioe.getMessage());
                s.recordException(ioe);
            } catch (Exception e) {
                LOG.error(e.toString(), e);
                throw e;
            }
            return null;
        });
    }

    // ===== Internal execution methods =====

    public String executeString(String url, String method, Map<String, String> headers, Object payload, Span span) {
        try (Response response = execute0(url, method, headers, payload);
             ResponseBody body = response.body()) {
            if (body != null) {
                return body.string();
            }
        } catch (IOException ioe) {
            LOG.error("HTTP executeString failed: " + url, ioe);
            span.setStatus(StatusCode.ERROR, ioe.getMessage());
            span.recordException(ioe);
        } catch (Exception e) {
            LOG.error(e.toString(), e);
            throw e;
        }
        return null;
    }

    public byte[] executeBytes(String url, String method, Map<String, String> headers, Object payload, Span span) {
        try (Response response = execute0(url, method, headers, payload);
             ResponseBody body = response.body()) {
            if (body != null) {
                return body.bytes();
            }
        } catch (IOException ioe) {
            LOG.error("HTTP executeBytes failed: " + url, ioe);
            span.setStatus(StatusCode.ERROR, ioe.getMessage());
            span.recordException(ioe);
        } catch (Exception e) {
            LOG.error(e.toString(), e);
            throw e;
        }
        return null;
    }

    private Response execute0(String url, String method, Map<String, String> headers, Object payload) throws IOException {
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

        Response response = okHttpClient.newCall(request).execute();

        // Inject status code into current span
        injectStatusCodeToCurrentSpan(response);
        return response;
    }

    public Response multipart(String url, Map<String, String> headers, Map<String, Object> payload) throws IOException {
        Request.Builder builder = new Request.Builder().url(url);
        if (headers != null && !headers.isEmpty()) {
            headers.forEach(builder::addHeader);
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
        Request request = builder.post(multipartBody).build();
        Response response = okHttpClient.newCall(request).execute();

        // Inject status code into current span (same as execute0)
        injectStatusCodeToCurrentSpan(response);
        return response;
    }

    // ===== Shared helper for span status code injection =====

    private void injectStatusCodeToCurrentSpan(Response response) {
        Span currentSpan = Span.current();
        if (currentSpan != null && currentSpan != Span.getInvalid()) {
            int statusCode = response.code();
            currentSpan.setAttribute("http.status_code", statusCode);
            if (statusCode >= 400) {
                currentSpan.setStatus(StatusCode.ERROR, "HTTP " + statusCode);
            }
        }
    }

    // ===== Observability wrapper =====
    @FunctionalInterface
    private interface HttpClientCall<T> {
        T call(String url, String method, Map<String, String> headers, Object payload, Span span) throws Exception;
    }

    private <T> T tracedCall(String url, String method, Map<String, String> headers, Object payload, HttpClientCall<T> call) {
        String host = extractHost(url);
        Span span = TRACER.spanBuilder("http.client.request")
            .setAttribute("http.method", method)
            .setAttribute("http.url", url)
            .setAttribute("server.address", host)
            .startSpan();

        long startTime = System.nanoTime();
        boolean success = true;

        try (Scope scope = span.makeCurrent()) {
            return call.call(url, method, headers, payload, span);
        } catch (Exception e) {
            success = false;
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw new RuntimeException("HTTP request failed", e);
        } finally {
            span.end();
            double latency = (System.nanoTime() - startTime) / 1_000_000_000.0;
            Attributes attrs = Attributes.of(
                AttributeKey.stringKey("http.method"), method,
                AttributeKey.stringKey("server.address"), host,
                AttributeKey.stringKey("http.success"), String.valueOf(success)
            );
            HTTP_REQUEST_COUNT.add(1, attrs);
            HTTP_LATENCY_HISTOGRAM.record(latency, attrs);
            if (!success) {
                HTTP_ERROR_COUNT.add(1, attrs);
            }
        }
    }

    // ===== Utility =====
    private static String extractHost(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            int port = uri.getPort();
            if (port != -1) {
                return host + ":" + port;
            }
            return host;
        } catch (URISyntaxException e) {
            return "unknown";
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
        public long contentLength() throws IOException {
            return inputStream.available() == 0 ? -1 : inputStream.available();
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            IOUtil.copy(inputStream, sink);
        }
    }
}

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
package com.agentsflex.core.model.chat.tool;

import com.agentsflex.core.message.ToolCall;
import com.agentsflex.core.observability.Observability;
import com.agentsflex.core.observability.ObservabilityRuntime;
import com.agentsflex.core.observability.SensitiveDataSanitizer;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.io.File;
import java.io.InputStream;
import java.util.Map;
import java.util.WeakHashMap;

public class ToolObservabilityInterceptor implements ToolInterceptor {
    private static final int MAX_CONTENT_LENGTH_FOR_SPAN = 4000;

    private static final Map<ObservabilityRuntime, Instruments> INSTRUMENTS = new WeakHashMap<>();

    private static final class Instruments {
        private final Tracer tracer;
        private final LongCounter callCount;
        private final DoubleHistogram latency;
        private final LongCounter errorCount;

        private Instruments(ObservabilityRuntime runtime) {
            this.tracer = runtime.getTracer();
            Meter meter = runtime.getMeter();
            this.callCount = meter.counterBuilder("tool.call.count")
                .setDescription("Total number of tool calls")
                .build();
            this.latency = meter.histogramBuilder("tool.call.latency")
                .setDescription("Tool call latency in seconds")
                .setUnit("s")
                .build();
            this.errorCount = meter.counterBuilder("tool.call.error.count")
                .setDescription("Total number of tool call errors")
                .build();
        }
    }

    @Override
    public Object intercept(ToolContext context, ToolChain chain) throws Exception {
        Tool tool = context.getTool();
        String toolName = tool.getName();
        if (!Observability.isEnabled() || Observability.isToolExcluded(toolName)) {
            return chain.proceed(context);
        }

        Instruments instruments = instruments();
        Span span = instruments.tracer.spanBuilder("tool." + toolName)
            .setAttribute("tool.name", toolName)
            .setAttribute("gen_ai.tool.name", toolName)
            .startSpan();
        Observability.enrichSpan(span);
        long startTimeNanos = System.nanoTime();

        try (Scope ignored = span.makeCurrent()) {
            if (Observability.isContentCaptureEnabled()) {
                ToolCall toolCall = context.getToolCall();
                String arguments = toolCall == null ? null : toolCall.getArguments();
                String safeArguments = SensitiveDataSanitizer.sanitizeJson(arguments, MAX_CONTENT_LENGTH_FOR_SPAN);
                if (safeArguments != null) {
                    span.setAttribute("tool.arguments", safeArguments);
                }
            }

            Object result = chain.proceed(context);
            if (Observability.isContentCaptureEnabled() && result != null) {
                span.setAttribute("tool.result", safeResult(result));
            }
            recordMetrics(instruments, toolName, true, null, startTimeNanos);
            return result;
        } catch (RuntimeException | Error error) {
            recordError(instruments, span, error, toolName, startTimeNanos);
            throw error;
        } catch (Exception error) {
            recordError(instruments, span, error, toolName, startTimeNanos);
            throw error;
        } finally {
            span.end();
        }
    }

    private static String safeResult(Object result) {
        if (result instanceof byte[]) {
            return "[binary_data]";
        }
        if (result instanceof InputStream || result instanceof File) {
            return "[stream_or_file]";
        }
        return SensitiveDataSanitizer.sanitizeObject(result, MAX_CONTENT_LENGTH_FOR_SPAN);
    }

    private static void recordMetrics(Instruments instruments, String toolName, boolean success,
                                      String errorType, long startTimeNanos) {
        double latencySeconds = (System.nanoTime() - startTimeNanos) / 1_000_000_000.0;
        AttributesBuilder builder = Attributes.builder()
            .put("tool.name", toolName)
            .put("tool.success", success);
        if (errorType != null) {
            builder.put("error.type", errorType);
        }
        Attributes attrs = builder.build();
        instruments.callCount.add(1, attrs);
        instruments.latency.record(latencySeconds, attrs);
        if (!success) {
            instruments.errorCount.add(1, attrs);
        }
    }

    private static void recordError(Instruments instruments, Span span, Throwable error,
                                    String toolName, long startTimeNanos) {
        span.setStatus(StatusCode.ERROR, error.getMessage());
        span.recordException(error);
        recordMetrics(instruments, toolName, false, error.getClass().getName(), startTimeNanos);
    }

    private static Instruments instruments() {
        ObservabilityRuntime runtime = Observability.currentRuntime();
        synchronized (INSTRUMENTS) {
            Instruments instruments = INSTRUMENTS.get(runtime);
            if (instruments == null) {
                instruments = new Instruments(runtime);
                INSTRUMENTS.put(runtime, instruments);
            }
            return instruments;
        }
    }
}

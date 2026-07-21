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

/**
 * ToolExecutor 的 OpenTelemetry 拦截器，为每次工具调用创建 Span 并记录调用量、耗时和错误指标。
 *
 * <p>工具参数和返回值可能包含凭证、文件或大量业务内容，因此默认不采集；开启内容采集后仍会经过脱敏、
 * 类型保护和长度限制。</p>
 */
public class ToolObservabilityInterceptor implements ToolInterceptor {
    /** 工具参数或返回值写入 Span 属性时允许保留的最大字符数。 */
    private static final int MAX_CONTENT_LENGTH_FOR_SPAN = 4000;

    /**
     * runtime 到工具埋点 instrument 的弱键缓存。
     * 每条 TelemetryRoute 必须使用自己的 Tracer/Meter；弱键兼顾 instrument 复用和 Route 下线后的回收。
     */
    private static final Map<ObservabilityRuntime, Instruments> INSTRUMENTS = new WeakHashMap<>();

    /** 某个 ObservabilityRuntime 专属的一组工具 Tracer 和 Metrics instrument。 */
    private static final class Instruments {
        /** 创建工具调用 Span 的 Tracer。 */
        private final Tracer tracer;

        /** 记录全部工具调用次数的 Counter。 */
        private final LongCounter callCount;

        /** 记录工具调用耗时的 Histogram，单位为秒。 */
        private final DoubleHistogram latency;

        /** 只记录失败工具调用次数的 Counter。 */
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

        // 工具内部若继续调用模型或 HTTP，它们会自然成为当前工具 Span 的子节点。
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
        // 二进制、文件和流既不适合序列化，也可能消耗大量内存，只记录稳定的类型占位符。
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
        // WeakHashMap 非线程安全，必须同步完成“查找或创建”。
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

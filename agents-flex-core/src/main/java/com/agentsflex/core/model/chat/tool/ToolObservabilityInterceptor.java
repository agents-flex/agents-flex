/*
 *  Copyright (c) 2023-2025, Agents-Flex (fuhai999@gmail.com).
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


import com.agentsflex.core.observability.Observability;
import com.alibaba.fastjson2.JSON;
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
import java.util.regex.Pattern;

/**
 * 增强版工具可观测性拦截器，支持：
 * - 全局/工具级开关
 * - JSON 结构化参数与结果
 * - 自动脱敏敏感字段
 * - 错误类型分类
 * - 类型安全结果处理
 */
public class ToolObservabilityInterceptor implements ToolInterceptor {

    private static final Tracer TRACER = Observability.getTracer();
    private static final Meter METER = Observability.getMeter();

    private static final LongCounter TOOL_CALL_COUNT = METER.counterBuilder("tool.call.count")
        .setDescription("Total number of tool calls")
        .build();

    private static final DoubleHistogram TOOL_LATENCY_HISTOGRAM = METER.histogramBuilder("tool.call.latency")
        .setDescription("Tool call latency in seconds")
        .setUnit("s")
        .build();

    private static final LongCounter TOOL_ERROR_COUNT = METER.counterBuilder("tool.call.error.count")
        .setDescription("Total number of tool call errors")
        .build();

    // 长度限制（OpenTelemetry 推荐单个 attribute ≤ 12KB，此处保守）
    private static final int MAX_JSON_LENGTH_FOR_SPAN = 4000;

    // 敏感字段正则（匹配 key，不区分大小写）
    private static final Pattern SENSITIVE_KEY_PATTERN = Pattern.compile(
        ".*(password|token|secret|key|auth|credential|cert|session).*",
        Pattern.CASE_INSENSITIVE
    );


    @Override
    public Object intercept(ToolContext context, ToolChain chain) throws Exception {
        Tool tool = context.getTool();
        String toolName = tool.getName();

        // 动态开关：全局关闭 或 工具在黑名单中
        if (!Observability.isEnabled() || Observability.isToolExcluded(toolName)) {
            return chain.proceed(context);
        }

        Span span = TRACER.spanBuilder("tool." + toolName)
            .setAttribute("tool.name", toolName)
            .startSpan();

        long startTimeNanos = System.nanoTime();

        try (Scope ignored = span.makeCurrent()) {

            // 记录脱敏后的参数（JSON）
            Map<String, Object> args = context.getArgsMap();
            if (args != null && !args.isEmpty()) {
                String safeArgsJson = safeToJson(args);
                span.setAttribute("tool.arguments", safeArgsJson);
            }

            // 执行工具
            Object result = chain.proceed(context);

            // 记录结果（成功）
            if (result != null) {
                String safeResult = safeToString(result);
                if (safeResult.length() > MAX_JSON_LENGTH_FOR_SPAN) {
                    safeResult = safeResult.substring(0, MAX_JSON_LENGTH_FOR_SPAN) + "...";
                }
                span.setAttribute("tool.result", safeResult);
            }

            recordMetrics(toolName, true, null, startTimeNanos);
            return result;

        } catch (Exception e) {
            recordError(span, e, toolName, startTimeNanos);
            throw e;
        } finally {
            span.end();
        }
    }

    // 安全转为 JSON，自动脱敏
    private String safeToJson(Object obj) {
        try {
            String json = JSON.toJSONString(obj);
            return redactSensitiveFields(json);
        } catch (Exception e) {
            return "[JSON_SERIALIZATION_ERROR]";
        }
    }

    // 简单脱敏：将敏感 key 对应的 value 替换为 "***"
    private String redactSensitiveFields(String json) {
        // 简单实现：按行处理（适用于格式化 JSON）
        // 更严谨可用 JSON parser 遍历，但性能低；此处平衡安全与性能
        String[] lines = json.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon > 0) {
                String keyPart = line.substring(0, colon);
                if (SENSITIVE_KEY_PATTERN.matcher(keyPart).matches()) {
                    int valueStart = colon + 1;
                    // 找到 value 开始和结束（简单处理 string/value）
                    if (valueStart < line.length()) {
                        char firstChar = line.charAt(valueStart);
                        if (firstChar == '"' || firstChar == '\'') {
                            // 字符串值
                            int endQuote = line.indexOf(firstChar, valueStart + 1);
                            if (endQuote > valueStart) {
                                lines[i] = line.substring(0, valueStart + 1) + "***" + line.substring(endQuote);
                            }
                        } else {
                            // 非字符串值（截至逗号或行尾）
                            int endValue = line.indexOf(',', valueStart);
                            if (endValue == -1) endValue = line.length();
                            lines[i] = line.substring(0, valueStart) + " \"***\"" + line.substring(endValue);
                        }
                    }
                }
            }
        }
        return String.join("\n", lines);
    }

    // 类型安全的 toString
    private String safeToString(Object obj) {
        if (obj == null) {
            return "null";
        }
        if (obj instanceof byte[]) {
            return "[binary_data]";
        }
        if (obj instanceof InputStream || obj instanceof File) {
            return "[stream_or_file]";
        }
        if (obj instanceof Map || obj instanceof Iterable) {
            return safeToJson(obj);
        }
        return obj.toString();
    }

    private void recordMetrics(String toolName, boolean success, String errorType, long startTimeNanos) {
        double latencySeconds = (System.nanoTime() - startTimeNanos) / 1_000_000_000.0;
        AttributesBuilder builder = Attributes.builder()
            .put("tool.name", toolName)
            .put("tool.success", success);
        if (errorType != null) {
            builder.put("error.type", errorType);
        }
        Attributes attrs = builder.build();

        TOOL_CALL_COUNT.add(1, attrs);
        TOOL_LATENCY_HISTOGRAM.record(latencySeconds, attrs);
        if (!success) {
            TOOL_ERROR_COUNT.add(1, attrs);
        }
    }

    private void recordError(Span span, Exception e, String toolName, long startTimeNanos) {
        span.setStatus(StatusCode.ERROR, e.getMessage());
        span.recordException(e);

        // 错误分类：业务异常（可预期） vs 系统异常（不可预期）
        String errorType = e instanceof RuntimeException && !(e instanceof IllegalStateException) ? "business" : "system";
        recordMetrics(toolName, false, errorType, startTimeNanos);
    }
}

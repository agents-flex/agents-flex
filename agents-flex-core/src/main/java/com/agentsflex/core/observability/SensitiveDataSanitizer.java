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
package com.agentsflex.core.observability;

import com.alibaba.fastjson2.JSON;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 对显式允许采集的遥测内容进行结构化脱敏。
 *
 * <p>实现会先把 JSON 或普通对象转换成树状结构，再按字段名递归替换敏感值，最后执行长度限制。采用结构化
 * 处理而不是字符串替换，可以覆盖嵌套对象和数组，也能避免部分匹配破坏原始 JSON。</p>
 *
 * <p>该工具只能降低常见字段泄露风险，不能替代业务侧的数据授权和分类治理。</p>
 */
public final class SensitiveDataSanitizer {
    /** 命中敏感字段名后替换原值使用的固定占位符。 */
    private static final String REDACTED = "***";

    /**
     * 常见凭证类字段名匹配规则，不区分大小写，并兼容 api_key、api-key、apiKey 等写法。
     */
    private static final Pattern SENSITIVE_KEY_PATTERN = Pattern.compile(
        ".*(password|passwd|token|secret|api.?key|authorization|auth|credential|cookie|session|cert).*",
        Pattern.CASE_INSENSITIVE
    );

    private SensitiveDataSanitizer() {
    }

    /**
     * 脱敏 JSON 字符串。无法解析时不返回原文，因为非法 JSON 中同样可能包含凭证。
     */
    public static String sanitizeJson(String json, int maxLength) {
        if (json == null) {
            return null;
        }
        try {
            Object parsed = JSON.parse(json);
            return truncate(JSON.toJSONString(sanitizeValue(parsed)), maxLength);
        } catch (Exception ignored) {
            // 非法 JSON 也可能包含凭证，解析失败时必须整体隐藏，不能为了调试而导出原文。
            return "[UNPARSEABLE_CONTENT_REDACTED]";
        }
    }

    /**
     * 把普通对象序列化为结构化数据后脱敏。序列化失败时只返回固定占位符。
     */
    public static String sanitizeObject(Object value, int maxLength) {
        if (value == null) {
            return null;
        }
        try {
            Object parsed = JSON.parse(JSON.toJSONString(value));
            return truncate(JSON.toJSONString(sanitizeValue(parsed)), maxLength);
        } catch (Exception ignored) {
            return "[SERIALIZATION_ERROR]";
        }
    }

    private static Object sanitizeValue(Object value) {
        // LinkedHashMap 保留输入字段顺序，便于排查数据，同时递归处理任意深度的对象和集合。
        if (value instanceof Map) {
            Map<?, ?> source = (Map<?, ?>) value;
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                String key = String.valueOf(entry.getKey());
                sanitized.put(key, isSensitiveKey(key) ? REDACTED : sanitizeValue(entry.getValue()));
            }
            return sanitized;
        }
        if (value instanceof Iterable) {
            List<Object> sanitized = new ArrayList<>();
            for (Object item : (Iterable<?>) value) {
                sanitized.add(sanitizeValue(item));
            }
            return sanitized;
        }
        return value;
    }

    private static boolean isSensitiveKey(String key) {
        return SENSITIVE_KEY_PATTERN.matcher(key).matches();
    }

    /**
     * 将内容限制为指定字符数。该限制用于控制 Span 属性体积，不表示数据库字段的字节上限。
     */
    public static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }
}

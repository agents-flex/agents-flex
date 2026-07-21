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

/** Structured sanitizer for content that is explicitly enabled for telemetry capture. */
public final class SensitiveDataSanitizer {
    private static final String REDACTED = "***";
    private static final Pattern SENSITIVE_KEY_PATTERN = Pattern.compile(
        ".*(password|passwd|token|secret|api.?key|authorization|auth|credential|cookie|session|cert).*",
        Pattern.CASE_INSENSITIVE
    );

    private SensitiveDataSanitizer() {
    }

    public static String sanitizeJson(String json, int maxLength) {
        if (json == null) {
            return null;
        }
        try {
            Object parsed = JSON.parse(json);
            return truncate(JSON.toJSONString(sanitizeValue(parsed)), maxLength);
        } catch (Exception ignored) {
            // Invalid JSON may still contain credentials; never export it verbatim.
            return "[UNPARSEABLE_CONTENT_REDACTED]";
        }
    }

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

    public static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }
}

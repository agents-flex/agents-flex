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
package com.agentsflex.observability.jdbc;

import com.alibaba.fastjson2.JSON;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.LinkData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 OTel 中不适合固定关系型列表达的属性、事件、Link 和聚合细节序列化为 JSON。
 *
 * <p>使用 LinkedHashMap 保持稳定顺序，便于数据库内容比较和人工排查。这里只做结构转换，不负责业务内容
 * 脱敏；敏感内容必须在写入 Span 属性之前由采集侧处理。</p>
 */
final class TelemetryJson {
    private TelemetryJson() {
    }

    static String attributes(Attributes attributes) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (attributes != null) {
            attributes.forEach((key, value) -> values.put(key.getKey(), value));
        }
        return JSON.toJSONString(values);
    }

    static String events(List<EventData> events) {
        // droppedAttributes 用于提示 SDK 因容量限制丢弃过属性，查询侧可据此判断数据是否完整。
        List<Map<String, Object>> values = new ArrayList<>();
        for (EventData event : events) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("name", event.getName());
            value.put("epochNanos", event.getEpochNanos());
            value.put("attributes", attributeMap(event.getAttributes()));
            value.put("droppedAttributes", event.getDroppedAttributesCount());
            values.add(value);
        }
        return JSON.toJSONString(values);
    }

    static String links(List<LinkData> links) {
        // Link 指向同一或其他 Trace 中的 Span，不等同于 parentSpanId，因此完整保存在独立 JSON 字段。
        List<Map<String, Object>> values = new ArrayList<>();
        for (LinkData link : links) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("traceId", link.getSpanContext().getTraceId());
            value.put("spanId", link.getSpanContext().getSpanId());
            value.put("traceFlags", link.getSpanContext().getTraceFlags().asHex());
            value.put("traceState", link.getSpanContext().getTraceState().asMap());
            value.put("attributes", attributeMap(link.getAttributes()));
            value.put("droppedAttributes", link.getTotalAttributeCount() - link.getAttributes().size());
            values.add(value);
        }
        return JSON.toJSONString(values);
    }

    static String value(Object value) {
        return JSON.toJSONString(value);
    }

    private static Map<String, Object> attributeMap(Attributes attributes) {
        Map<String, Object> values = new LinkedHashMap<>();
        attributes.forEach((key, value) -> values.put(key.getKey(), value));
        return values;
    }
}

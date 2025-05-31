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
package com.agentsflex.core.util.graalvm;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class JsInteropUtils {

    public static Value wrapJavaValueForJS(Context context, Object value) {
        if (value == null) {
            return context.asValue(null);
        }

        // 处理 LocalDateTime / LocalDate / ZonedDateTime -> JS Date
        if (value instanceof LocalDateTime) {
            return context.eval("js", "new Date('" + ((LocalDateTime) value).atZone(ZoneId.systemDefault()) + "')");
        }
        if (value instanceof LocalDate) {
            return context.eval("js", "new Date('" + ((LocalDate) value).atStartOfDay(ZoneId.systemDefault()) + "')");
        }
        if (value instanceof ZonedDateTime) {
            return context.eval("js", "new Date('" + (value) + "')");
        }
        if (value instanceof Date) {
            return context.eval("js", "new Date(" + ((Date) value).getTime() + ")");
        }

        // 处理 Map -> ProxyObject
        if (value instanceof Map) {
            return context.asValue(new ProxyMap((Map) value, context));
        }

        // 处理 List -> ProxyArray
        if (value instanceof List) {
            return context.asValue(new ProxyList((List) value, context));
        }

        // 处理 Set -> ProxyArray
        if (value instanceof Set) {
            return context.asValue(new ProxyList(new ArrayList<>((Set) value), context));
        }

        // 处理数组 -> ProxyArray
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<Object> list = IntStream.range(0, length)
                .mapToObj(i -> java.lang.reflect.Array.get(value, i))
                .collect(Collectors.toList());
            return context.asValue(new ProxyList(list, context));
        }

        // 默认处理：基本类型或 Java 对象直接返回
        return context.asValue(value);
    }


    // 双向转换：将 JS 值转为 Java 类型
    public static Object unwrapJsValue(Value value) {
        if (value.isHostObject()) {
            return value.asHostObject();
        } else if (value.hasMembers()) {
            Map<String, Object> map = new HashMap<>();
            for (String key : value.getMemberKeys()) {
                map.put(key, unwrapJsValue(value.getMember(key)));
            }
            return map;
        } else if (value.hasArrayElements()) {
            List<Object> list = new ArrayList<>();
            long size = value.getArraySize();
            for (long i = 0; i < size; i++) {
                list.add(unwrapJsValue(value.getArrayElement(i)));
            }
            return list;
        } else if (value.isDate()) {
            Instant instant = Instant.from(value.asDate());
            return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        } else {
            return value.as(Object.class);
        }
    }

}

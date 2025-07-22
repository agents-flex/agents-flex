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
package com.agentsflex.chain.node;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.graalvm.polyglot.Value;

import java.util.*;

public class GraalToFastjsonUtils {
    public static Object toFastJsonValue(Object obj) {
        if (obj == null) {
            return null;
        }

        if (obj instanceof Value) {
            Value value = (Value) obj;

            if (value.isNull()) {
                return null;
            }

            if (value.isBoolean()) {
                return value.as(Boolean.class);
            }

            if (value.isNumber()) {
                if (value.fitsInLong()) {
                    return value.as(Long.class);
                } else if (value.fitsInDouble()) {
                    return value.as(Double.class);
                } else {
                    return value.toString(); // e.g., BigInt
                }
            }

            if (value.isString()) {
                return value.as(String.class);
            }

            if (value.hasArrayElements()) {
                long size = value.getArraySize();
                JSONArray array = new JSONArray();
                for (long i = 0; i < size; i++) {
                    array.add(toFastJsonValue(value.getArrayElement(i)));
                }
                return array;
            }

            if (value.hasMembers()) {
                JSONObject object = new JSONObject();
                Set<String> keys = value.getMemberKeys();
                for (String key : keys) {
                    Value member = value.getMember(key);
                    // 排除函数
                    if (!member.canExecute()) {
                        object.put(key, toFastJsonValue(member));
                    }
                }
                return object;
            }

            return value.toString();
        }

        // 处理标准 Java 类型 ---------------------------------------

        if (obj instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) obj;
            JSONObject jsonObject = new JSONObject();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = Objects.toString(entry.getKey(), "null");
                Object convertedValue = toFastJsonValue(entry.getValue());
                jsonObject.put(key, convertedValue);
            }
            return jsonObject;
        }

        if (obj instanceof Collection) {
            Collection<?> coll = (Collection<?>) obj;
            JSONArray array = new JSONArray();
            for (Object item : coll) {
                array.add(toFastJsonValue(item));
            }
            return array;
        }

        if (obj.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(obj);
            JSONArray array = new JSONArray();
            for (int i = 0; i < length; i++) {
                Object item = java.lang.reflect.Array.get(obj, i);
                array.add(toFastJsonValue(item));
            }
            return array;
        }

        // 基本类型直接返回
        if (obj instanceof String ||
            obj instanceof Boolean ||
            obj instanceof Byte ||
            obj instanceof Short ||
            obj instanceof Integer ||
            obj instanceof Long ||
            obj instanceof Float ||
            obj instanceof Double) {
            return obj;
        }

        // 兜底：调用 toString
        return obj.toString();
    }

    /**
     * 转为 JSONObject（适合根是对象）
     */
    public static JSONObject toJSONObject(Object obj) {
        Object result = toFastJsonValue(obj);
        if (result instanceof JSONObject) {
            return (JSONObject) result;
        } else if (result instanceof Map) {
            return new JSONObject((Map) result);
        } else {
            return new JSONObject().fluentPut("value", result);
        }
    }
}

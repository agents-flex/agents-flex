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
package com.agentsflex.core.util;

import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONPath;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class JSONUtil {

    private static final Map<String, JSONPath> jsonPaths = new ConcurrentHashMap<>();

    public static JSONPath getJsonPath(String path) {
        return MapUtil.computeIfAbsent(jsonPaths, path, JSONPath::of);
    }

    public static double[] readDoubleArray(JSONObject jsonObject, String path) {
        if (jsonObject == null || path == null) {
            return null;
        }

        JSONPath jsonPath = getJsonPath(path);
        Object result = jsonPath.eval(jsonObject);

        if (result == null) {
            return null;
        }

        if (result instanceof List) {
            List<?> list = (List<?>) result;
            double[] array = new double[list.size()];
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                if (item instanceof Number) {
                    array[i] = ((Number) item).doubleValue();
                } else if (item == null) {
                    array[i] = 0.0; // 或抛异常，根据需求
                } else {
                    // 尝试转换字符串？或报错
                    try {
                        array[i] = Double.parseDouble(item.toString());
                    } catch (NumberFormatException e) {
                        return null; // 或抛异常
                    }
                }
            }
            return array;
        }

        return null;
    }

    public static String readString(JSONObject jsonObject, String path) {
        if (jsonObject == null || path == null) {
            return null;
        }

        JSONPath jsonPath = getJsonPath(path);
        Object result = jsonPath.eval(jsonObject);

        if (result == null) {
            return null;
        }

        if (result instanceof String) {
            return (String) result;
        }

        return null;
    }

    public static Integer readInteger(JSONObject jsonObject, String path) {
        if (jsonObject == null || path == null) {
            return null;
        }
        JSONPath jsonPath = getJsonPath(path);
        Object result = jsonPath.eval(jsonObject);
        if (result == null) {
            return null;
        }
        if (result instanceof Number) {
            return ((Number) result).intValue();
        }
        if (result instanceof String) {
            return Integer.parseInt((String) result);
        }
        throw new IllegalArgumentException("Invalid JSON path result type: " + result.getClass().getName());
    }

    public static Long readLong(JSONObject jsonObject, String path) {
        if (jsonObject == null || path == null) {
            return null;
        }
        JSONPath jsonPath = getJsonPath(path);
        Object result = jsonPath.eval(jsonObject);
        if (result == null) {
            return null;
        }
        if (result instanceof Number) {
            return ((Number) result).longValue();
        }
        if (result instanceof String) {
            return Long.getLong((String) result);
        }
        throw new IllegalArgumentException("Invalid JSON path result type: " + result.getClass().getName());
    }

    public static String detectErrorMessage(JSONObject jsonObject) {
        JSONObject errorObject = jsonObject.getJSONObject("error");
        if (errorObject == null) {
            return null;
        }
        String errorMessage = errorObject.getString("message");
        String errorCode = errorObject.getString("code");
        return errorCode == null ? errorMessage : (errorCode + ": " + errorMessage);
    }
}

package com.agentsflex.core.util;

import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONPath;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class JSONUtil {

    private static final Map<String, JSONPath> jsonPaths = new ConcurrentHashMap<>();

    public static double[] readDoubleArray(JSONObject jsonObject, String path) {
        if (jsonObject == null || path == null) {
            return null;
        }

        JSONPath jsonPath = jsonPaths.computeIfAbsent(path, JSONPath::of);
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

        JSONPath jsonPath = jsonPaths.computeIfAbsent(path, JSONPath::of);
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
        JSONPath jsonPath = jsonPaths.computeIfAbsent(path, JSONPath::of);
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
        JSONPath jsonPath = jsonPaths.computeIfAbsent(path, JSONPath::of);
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
}

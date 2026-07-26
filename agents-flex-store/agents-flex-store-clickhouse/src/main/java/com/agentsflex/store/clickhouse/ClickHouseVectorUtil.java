/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 * Licensed under the Apache License, Version 2.0.
 */
package com.agentsflex.store.clickhouse;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;

import java.util.ArrayList;
import java.util.List;

/** ClickHouse {@code Array(Float32)} 与 Java float 数组之间的转换工具。 */
final class ClickHouseVectorUtil {
    private ClickHouseVectorUtil() {}

    /**
     * 转成受控的 ClickHouse 数组文本。使用 Double 保证 1.0 等值不会被序列化成整数；调用方只会在
     * 完成有限数值检查后把该文本放入 {@code CAST(... AS Array(Float32))}，不包含可执行 SQL 片段。
     */
    static String toText(float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("ClickHouse vector cannot be null or empty");
        }
        List<Double> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("ClickHouse vector values must be finite");
            }
            values.add((double) value);
        }
        return JSON.toJSONString(values);
    }

    /** 解析 JDBC 返回的 ClickHouse 数组文本。 */
    static float[] fromText(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        JSONArray values = JSON.parseArray(text);
        float[] result = new float[values.size()];
        for (int i = 0; i < values.size(); i++) result[i] = values.getFloatValue(i);
        return result;
    }
}

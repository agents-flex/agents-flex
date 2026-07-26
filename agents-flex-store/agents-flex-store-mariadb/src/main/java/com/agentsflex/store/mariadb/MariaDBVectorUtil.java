/*
 * Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentsflex.store.mariadb;

import com.alibaba.fastjson2.JSON;

import java.util.List;

/** MariaDB VECTOR 文本格式转换工具。 */
final class MariaDBVectorUtil {

    private MariaDBVectorUtil() {
    }

    static String toText(float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("MariaDB document vector cannot be null or empty.");
        }
        StringBuilder result = new StringBuilder(vector.length * 8).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (!Float.isFinite(vector[i])) {
                throw new IllegalArgumentException("MariaDB vector values must be finite.");
            }
            if (i > 0) {
                result.append(',');
            }
            result.append(Float.toString(vector[i]));
        }
        return result.append(']').toString();
    }

    static float[] fromText(String vector) {
        if (vector == null) {
            return null;
        }
        List<Float> values = JSON.parseArray(vector, Float.class);
        float[] result = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = values.get(i);
        }
        return result;
    }
}

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
package com.agentsflex.store.pgvector;

import org.postgresql.util.PGobject;

import java.sql.SQLException;

/** PostgreSQL vector 类型与 Java 浮点数组之间的转换工具。 */
public class PgvectorUtil {
    /**
     * 将浮点数组转换为 PostgreSQL {@code vector} 类型参数。
     *
     * @param src 向量
     * @return 可直接绑定到 JDBC 参数的对象
     * @throws SQLException PGobject 设置值失败时抛出
     */
    public static PGobject toPgVector(float[] src) throws SQLException {
        PGobject vector = new PGobject();
        vector.setType("vector");
        if (src.length == 0) {
            vector.setValue("[]");
            return vector;
        }

        StringBuilder sb = new StringBuilder("[");
        for (double v : src) {
            sb.append(v);
            sb.append(",");
        }
        vector.setValue(sb.substring(0, sb.length() - 1) + "]");

        return vector;
    }

    /**
     * 将 PostgreSQL 返回的 {@code [x,y,...]} 文本转换为浮点数组。
     * 无法解析的单个元素按 0 处理。
     */
    public static float[] fromPgVector(String src) {
        if (src.equals("[]")) {
            return new float[0];
        }

        String[] strs = src.substring(1, src.length() - 1).split(",");
        float[] output = new float[strs.length];
        for (int i = 0; i < strs.length; i++) {
            try {
                output[i] = Float.parseFloat(strs[i]);
            } catch (Exception ignore) {
                output[i] = 0;
            }
        }
        return output;
    }
}

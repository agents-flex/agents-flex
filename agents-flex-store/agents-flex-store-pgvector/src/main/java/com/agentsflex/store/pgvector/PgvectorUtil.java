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

public class PgvectorUtil {
    /**
     * 转化为vector.
     * 如果需要half vector或者sparse vector 对应实现即可
     * @param src 向量
     * @return
     * @throws SQLException
     */
    public static PGobject toPgVector(double[] src) throws SQLException {
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

    public static double[] fromPgVector(String src) {
        if (src.equals("[]")) {
            return new double[0];
        }

        String[] strs = src.substring(1, src.length() - 1).split(",");
        double[] output = new double[strs.length];
        for (int i = 0; i < strs.length; i++) {
            try {
                output[i] = Double.parseDouble(strs[i]);
            } catch (Exception ignore) {
                output[i] = 0;
            }
        }
        return output;
    }
}

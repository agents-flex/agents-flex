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

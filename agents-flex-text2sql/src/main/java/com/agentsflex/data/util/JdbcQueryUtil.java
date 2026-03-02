package com.agentsflex.data.util;

import com.agentsflex.core.util.StringUtil;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class JdbcQueryUtil {


    /**
     * 执行查询 SQL，返回结果列表（每行是一个 Map）
     *
     * @param dataSource 数据源
     * @param sql        查询 SQL（支持 ? 占位符）
     * @param params     参数值数组
     * @return 查询结果列表
     * @throws SQLException SQL 异常
     */
    public static List<Map<String, Object>> query(DataSource dataSource, String sql, List<Object> params) throws SQLException {
        if (StringUtil.noText(sql)) {
            throw new SQLException("SQL语句不能为空");
        }

        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            // 设置参数
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }

            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();

                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        String label = metaData.getColumnLabel(i);
                        Object value = rs.getObject(i);
                        row.put(label, value);
                    }
                    result.add(row);
                }
            }
        }
        return result;
    }

    /**
     * 执行查询 SQL，返回单行结果（若无结果则返回 null）
     */
    public static Map<String, Object> queryOne(DataSource dataSource, String sql, List<Object> params) throws SQLException {
        List<Map<String, Object>> list = query(dataSource, sql, params);
        return list.isEmpty() ? null : list.get(0);
    }


    /**
     * 执行查询 SQL，返回单个值（如 count、sum 等）
     */
    public static Object queryValue(DataSource dataSource, String sql, List<Object> params) throws SQLException {
        Map<String, Object> row = queryOne(dataSource, sql, params);
        if (row == null || row.isEmpty()) {
            return null;
        }
        return row.values().iterator().next();
    }
}

package com.agentsflex.data.entity;

import com.agentsflex.data.jdbc.JdbcTableBuilder;
import com.agentsflex.data.util.DataSourceBuilder;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class JdbcDataSourceInfo extends DataSourceInfo {

    private static final Map<String, DataSource> dataSourceMap = new ConcurrentHashMap<>();

    private String jdbcUrl;
    private String username;
    private String password;
    private String schema;

    @Override
    public DataSource getDataSource() {
        String cacheKey = jdbcUrl + ":" + username + ":" + password;
        return dataSourceMap.computeIfAbsent(cacheKey, s -> {
            Map<String, String> properties = new HashMap<>();
            properties.put("url", jdbcUrl);
            properties.put("username", username);
            properties.put("password", password);
            return new DataSourceBuilder(properties).build();
        });
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public void buildTables() {
        new JdbcTableBuilder(this).build();
    }
}

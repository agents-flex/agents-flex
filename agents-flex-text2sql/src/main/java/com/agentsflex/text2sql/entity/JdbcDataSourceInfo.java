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
package com.agentsflex.text2sql.entity;

import com.agentsflex.text2sql.jdbc.JdbcTableBuilder;
import com.agentsflex.text2sql.jdbc.dialect.IDialect;
import com.agentsflex.text2sql.util.DataSourceBuilder;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class JdbcDataSourceInfo extends DataSourceInfo {

    private static final Map<String, DataSource> dataSourceMap = new ConcurrentHashMap<>();

    private String jdbcUrl;
    private String username;
    private String password;
    private String schema;
    private DataSource dataSource;

    // 指定特定的表名
    private IDialect dialect = IDialect.DEFAULT;
    private Function<TableInfo, Boolean> tableFilter;
    private Function<ColumnInfo, Boolean> columnFilter;


    @Override
    public DataSource getDataSource() {
        if (dataSource != null) {
            return dataSource;
        }

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
        new JdbcTableBuilder(this, dialect).build(this.tableFilter, this.columnFilter);
    }

    public void setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }


    public IDialect getDialect() {
        return dialect;
    }

    public void setDialect(IDialect dialect) {
        this.dialect = dialect;
    }

    public Function<TableInfo, Boolean> getTableFilter() {
        return tableFilter;
    }

    public void setTableFilter(Function<TableInfo, Boolean> tableFilter) {
        this.tableFilter = tableFilter;
    }

    public Function<ColumnInfo, Boolean> getColumnFilter() {
        return columnFilter;
    }

    public void setColumnFilter(Function<ColumnInfo, Boolean> columnFilter) {
        this.columnFilter = columnFilter;
    }
}

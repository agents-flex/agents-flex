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
package com.agentsflex.text2sql.jdbc;

import com.agentsflex.text2sql.entity.ColumnInfo;
import com.agentsflex.text2sql.entity.JdbcDataSourceInfo;
import com.agentsflex.text2sql.entity.TableInfo;
import com.agentsflex.text2sql.jdbc.dialect.IDialect;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 代码生成器。
 *
 * @author michael
 */
public class JdbcTableBuilder {

    protected JdbcDataSourceInfo dataSource;
    protected IDialect dialect = IDialect.DEFAULT;

    public JdbcTableBuilder(JdbcDataSourceInfo dataSource) {
        this.dataSource = dataSource;
    }

    public JdbcTableBuilder(JdbcDataSourceInfo dataSource, IDialect dialect) {
        this.dataSource = dataSource;
        this.dialect = dialect;
    }


    public void build(Function<TableInfo, Boolean> tableFilter, Function<ColumnInfo, Boolean> columnFilter) {
        try (Connection conn = dataSource.getDataSource().getConnection()) {
            DatabaseMetaData dbMeta = conn.getMetaData();
            List<TableInfo> tableInfos = buildTables(dbMeta, conn, tableFilter, columnFilter);
            dataSource.setTables(tableInfos);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected void buildPrimaryKey(DatabaseMetaData dbMeta, Connection conn, TableInfo table) throws SQLException {
        try (ResultSet rs = dbMeta.getPrimaryKeys(conn.getCatalog(), null, table.getName())) {
            while (rs.next()) {
                String primaryKey = rs.getString("COLUMN_NAME");
                table.addPrimaryKey(primaryKey);
            }
        }
    }

    protected List<TableInfo> buildTables(DatabaseMetaData dbMeta, Connection conn, Function<TableInfo, Boolean> tableFilter, Function<ColumnInfo, Boolean> columnFilter) throws SQLException {
        String schemaName = dataSource.getSchema();
        List<TableInfo> tables = new ArrayList<>();
        try (ResultSet rs = getTablesResultSet(dbMeta, conn, schemaName)) {
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");

                TableInfo table = new TableInfo();

                table.setSchema(schemaName);
                table.setName(tableName);

                String remarks = rs.getString("REMARKS");
                table.setDescription(remarks);

                buildPrimaryKey(dbMeta, conn, table);

                dialect.buildTableColumns(schemaName, table, dbMeta, conn, columnFilter);

                if (tableFilter != null && Boolean.TRUE.equals(tableFilter.apply(table))) {
                    continue;
                }

                tables.add(table);
            }
        }
        return tables;
    }


    protected ResultSet getTablesResultSet(DatabaseMetaData dbMeta, Connection conn, String schema) throws SQLException {
        return dialect.getTablesResultSet(dbMeta, conn, schema, new String[]{"TABLE", "VIEW"});
    }


}

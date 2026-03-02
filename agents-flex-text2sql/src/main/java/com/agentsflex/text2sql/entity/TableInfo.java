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

import java.util.ArrayList;
import java.util.List;

public class TableInfo extends BaseInfo {

    protected String schema;
    private List<ColumnInfo> columns;
    private List<String> primaryKeys;

    public List<ColumnInfo> getColumns() {
        return columns;
    }

    public void setColumns(List<ColumnInfo> columns) {
        this.columns = columns;
    }

    public void addPrimaryKey(String primaryKey) {
        if (primaryKeys == null) {
            primaryKeys = new ArrayList<>();
        }
        primaryKeys.add(primaryKey);
    }

    public List<String> getPrimaryKeys() {
        return primaryKeys;
    }

    public boolean hasPrimaryKey(String primaryKey) {
        return primaryKeys != null && primaryKeys.contains(primaryKey);
    }

    public void setSchema(String schemaName) {
        this.schema = schemaName;
    }

    public String getSchema() {
        return schema;
    }

    public void addColumn(ColumnInfo column) {
        if (columns == null) {
            columns = new ArrayList<>();
        }
        columns.add(column);
    }
}


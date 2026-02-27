package com.agentsflex.data.entity;

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


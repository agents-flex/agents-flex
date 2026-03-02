package com.agentsflex.data.entity;

import com.agentsflex.data.jdbc.JdbcTableBuilder;

import javax.sql.DataSource;
import java.util.List;

public abstract class DataSourceInfo extends BaseInfo {

    private List<TableInfo> tables;

    public abstract DataSource getDataSource();

    public List<TableInfo> getTables() {
        return tables;
    }

    public void setTables(List<TableInfo> tables) {
        this.tables = tables;
    }


}

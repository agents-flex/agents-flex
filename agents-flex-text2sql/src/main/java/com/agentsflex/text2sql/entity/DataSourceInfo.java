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

import javax.sql.DataSource;
import java.util.List;
import java.util.function.Function;

public abstract class DataSourceInfo extends BaseInfo {

    private List<TableInfo> tables;

    /**
     * 列信息解析器 (Column Resolver)
     * <p>
     * 允许用户自定义获取表字段的逻辑。
     * 典型应用场景：
     * 1. 权限控制：根据当前用户角色过滤敏感字段。
     * 2. 动态脱敏：对特定字段进行标记或替换。
     * </p>
     * 如果未设置，则默认使用 {@link TableInfo#getColumns()}。
     */
    private Function<TableInfo, List<ColumnInfo>> columnResolver;

    /**
     * 解析并获取底层的 JDBC 数据源
     * <p>
     * 子类必须实现此方法，以提供实际的数据库连接能力。
     * 该方法可能被频繁调用，建议子类内部做好缓存或连接池管理。
     * </p>
     *
     * @return javax.sql.DataSource 实例，不应为 null
     */
    public abstract DataSource getJdbcDataSource();

    public List<TableInfo> getTables() {
        return tables;
    }

    public void setTables(List<TableInfo> tables) {
        this.tables = tables;
    }

    public Function<TableInfo, List<ColumnInfo>> getColumnResolver() {
        return columnResolver;
    }

    public void setColumnResolver(Function<TableInfo, List<ColumnInfo>> columnResolver) {
        this.columnResolver = columnResolver;
    }

    /**
     * 获取指定表的列信息
     *
     * @param tableInfo 表信息
     * @return 列信息列表
     */
    public List<ColumnInfo> resolveTableColumns(TableInfo tableInfo) {
        // 优先使用自定义解析器
        if (columnResolver != null) {
            return columnResolver.apply(tableInfo);
        }

        // 降级使用默认实现
        return tableInfo.getColumns();
    }

}

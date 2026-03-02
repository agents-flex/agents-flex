package com.agentsflex.data.tools;

import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.core.model.chat.tool.ToolScanner;
import com.agentsflex.core.model.chat.tool.annotation.ToolDef;
import com.agentsflex.core.model.chat.tool.annotation.ToolParam;
import com.agentsflex.data.entity.ColumnInfo;
import com.agentsflex.data.entity.DataSourceInfo;
import com.agentsflex.data.entity.JdbcDataSourceInfo;
import com.agentsflex.data.entity.TableInfo;
import com.agentsflex.data.util.JdbcQueryUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DataTools - AI Data Query Toolset
 * <p>
 * All Function Call methods follow the "soft failure" principle:
 * - Success: Return structured text (Markdown/JSON)
 * - Failure: Return string in "Error: xxx" format, allowing AI to self-correct
 * </p>
 *
 * @author Michael Yang
 */
public class DataTools {

    private static final String ERROR_PREFIX = "Error: ";

    private final List<DataSourceInfo> dataSourceInfos;

    public DataTools(List<DataSourceInfo> dataSourceInfos) {
        this.dataSourceInfos = dataSourceInfos != null ? dataSourceInfos : new ArrayList<>();
    }

    // ========================================================================
    // [Tool Builder] listTables - Returns Tool object (handled by ToolScanner)
    // ========================================================================

    public Tool buildListTablesTool() {
        StringBuilder sb = new StringBuilder();
        if (!dataSourceInfos.isEmpty()) {
            for (DataSourceInfo dataSourceInfo : dataSourceInfos) {
                sb.append("<data_source>\n")
                    .append("  <name>").append(dataSourceInfo.genName()).append("</name>\n")
                    .append("  <description>").append(
                        dataSourceInfo.genDescription() != null ?
                            dataSourceInfo.getDescription() : "No description").append("</description>\n")
                    .append("</data_source>\n");
            }
        } else {
            sb.append("<!-- No available data sources -->\n");
        }

        String description =
            "[Query Process - Step 1] Retrieve a list of all table names under the specified data source.\n\n" +
                "## Use Cases:\n" +
                "- When the user requests data query but is uncertain about table names\n" +
                "- When you need to confirm which tables are available under a specific data source\n" +
                "- Before writing SQL, you need to confirm whether the target table exists\n\n" +
                "## Important Rules:\n" +
                "- dataSourceName must be selected from the <available_data_sources> list below\n" +
                "- Match names strictly, case-sensitive, do not fabricate\n" +
                "- If the user does not specify a data source, first list available options for selection\n\n" +
                "<available_data_sources>\n" +
                sb +
                "</available_data_sources>";

        return Tool.builder()
            .name("listTables")
            .description(description)
            .addParameter(
                Parameter.builder()
                    .name("dataSourceName")
                    .type("string")
                    .description("The name of the data source, must be obtained from available_data_sources, do not fabricate.")
                    .required(true)
                    .build()
            ).function(argsMap -> {
                String dataSourceName = (String) argsMap.get("dataSourceName");
                if (dataSourceName == null || dataSourceName.isEmpty()) {
                    return ERROR_PREFIX + "Current data source is empty, please specify a data source name.";
                } else {
                    dataSourceName = dataSourceName.trim();
                }

                for (DataSourceInfo dataSourceInfo : dataSourceInfos) {
                    if (dataSourceInfo.getName().equalsIgnoreCase(dataSourceName)) {
                        // Return String description, keep style consistent
                        return formatTableList(dataSourceInfo);
                    }
                }
                return ERROR_PREFIX + "Data source not found: '" + dataSourceName + "'";
            })
            .build();
    }

    /**
     * Format table list into AI-friendly text
     */
    private String formatTableList(DataSourceInfo dataSourceInfo) {
        List<TableInfo> tables = dataSourceInfo.getTables();
        if (tables == null || tables.isEmpty()) {
            return "📭 No available tables under data source '" + dataSourceInfo.getName() + "'";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📋 Data Source: `").append(dataSourceInfo.getName()).append("`\n")
            .append("Available Tables Count: ").append(tables.size()).append("\n\n");

        sb.append("| Table Name | Description |\n")
            .append("|------------|-------------|\n");

        for (TableInfo table : tables) {
            sb.append("| `").append(safeStr(table.genName())).append("`")
                .append(" | ").append(safeStr(table.genDescription()))
                .append(" |\n");
        }

        sb.append("\n> 💡 Tip: When calling listTableColumns, tableName must use the `Table Name` above");
        return sb.toString();
    }

    // ========================================================================
    // [Step 2] listTableColumns - Get table schema (Returns Markdown string)
    // ========================================================================

    @ToolDef(
        name = "listTableColumns",
        description = "[Step 2] Get field structure description for the specified table. Returns table schema text in Markdown format, including: field name, data type, length, nullable, primary key, auto-increment, field comment. Must call this tool to confirm column names before writing SQL."
    )
    public String listTableColumns(
        @ToolParam(name = "dataSourceName", description = "Data source name, must be from the available_data_sources list") String dataSourceName,
        @ToolParam(name = "tableName", description = "Table name, must be from the return result of listTables, case-sensitive") String tableName
    ) {
        // 🔍 Parameter validation
        if (dataSourceName == null || dataSourceName.trim().isEmpty()) {
            return ERROR_PREFIX + "dataSourceName cannot be empty, please select from available_data_sources";
        }
        if (tableName == null || tableName.trim().isEmpty()) {
            return ERROR_PREFIX + "tableName cannot be empty, please call listTables first to get available table names";
        }

        // 🔍 Find data source
        DataSourceInfo targetDataSource = findDataSource(dataSourceName);
        if (targetDataSource == null) {
            return ERROR_PREFIX + "Data source '" + dataSourceName + "' not found, available options: [" + getAvailableDataSourceNames() + "]";
        }

        // 🔍 Find table
        TableInfo targetTable = findTable(targetDataSource, tableName);
        if (targetTable == null) {
            return ERROR_PREFIX + "Table '" + tableName + "' does not exist, available tables: " + getAvailableTableNames(targetDataSource.getTables());
        }

        // 🔍 Get and format columns
        List<ColumnInfo> columns = targetTable.getColumns();
        if (columns == null || columns.isEmpty()) {
            return ERROR_PREFIX + "No field definitions found under table '" + tableName + "', metadata may not be loaded correctly";
        }

        return formatTableSchema(tableName, columns);
    }

    /**
     * Format ColumnInfo list into AI-friendly Markdown table
     */
    private String formatTableSchema(String tableName, List<ColumnInfo> columns) {
        StringBuilder sb = new StringBuilder();

        sb.append("📊 Table Schema: `").append(tableName).append("`\n")
            .append("Total Fields: ").append(columns.size()).append("\n\n");

        sb.append("| Field Name | Type | Primary Key | Auto Increment | Comment |\n")
            .append("|------------|------|----------------|-------------------|---------|\n");

        for (ColumnInfo column : columns) {
            sb.append("| `").append(safeStr(column.genName())).append("`")
                .append(" | ").append(safeStr(column.getType()))
//                .append(" | ").append(safeNum(column.getLength()))
//                .append(" | ").append(formatNullable(column.getNullable()))
                .append(" | ").append(column.isPrimaryKey() ? "✅" : "❌")
                .append(" | ").append(column.isAutoIncrement() ? "✅" : "❌")
                .append(" | ").append(safeStr(column.genDescription()))
                .append(" |\n");
        }

        sb.append("\n> 💡 Tip: When writing SQL, please use the `Field Name` above, do not fabricate.");
        return sb.toString();
    }

    // ========================================================================
    // [Execute Query] queryDataList - Return multi-row results (JSON string)
    // ========================================================================

    @ToolDef(
        name = "queryDataList",
        description = "[Execute Query - List] Execute SQL query and return multi-row results. Suitable for querying lists, multiple records scenarios. Returns JSON array string. Security restriction: SELECT read-only statements only, UPDATE/DELETE/INSERT/DROP operations are prohibited. Dynamic values must use ? placeholders."
    )
    public String queryDataList(
        @ToolParam(name = "dataSourceName", description = "Data source name") String dataSourceName,
        @ToolParam(name = "sql", description = "Standard SQL SELECT statement. If containing dynamic values, must use '?' as placeholder, direct string concatenation is prohibited to prevent SQL injection. Recommended to add reasonable LIMIT to restrict returned rows") String sql,
        @ToolParam(name = "params", description = "List of parameter values corresponding to '?' placeholders in SQL, order must match placeholders. Pass empty list [] if no parameters") List<Object> params
    ) {
        // SQL security validation
        String validateError = validateSqlReadOnly(sql);
        if (validateError != null) {
            return ERROR_PREFIX + validateError;
        }

        // Get data source
        DataSource dataSource = getDataSource(dataSourceName);
        if (dataSource == null) {
            return ERROR_PREFIX + "Invalid data source name: '" + dataSourceName + "', available: [" + getAvailableDataSourceNames() + "]";
        }

        try {
            List<Map<String, Object>> result = JdbcQueryUtil.query(dataSource, sql, safeParams(params));
            // Return formatted JSON, pretty=true for easier AI parsing
            return JSON.toJSONString(result, JSONWriter.Feature.PrettyFormat);
        } catch (SQLException e) {
            // Log (replace with logger in production environment)
            System.err.println("[DataTools] SQL execution exception: " + e.getMessage());
            return ERROR_PREFIX + "SQL execution failed: " + e.getMessage() + ". Please check SQL syntax, table name, column name correctness";
        }
    }

    // ========================================================================
    // [Execute Query] querySingleRow - Return single-row result (JSON string)
    // ========================================================================

    @ToolDef(
        name = "querySingleRow",
        description = "[Execute Query - Single Row] Execute SQL query and return single-row result. Suitable for querying details by ID, getting the latest record, etc. Returns JSON object string. If multiple rows exist, only the first row is returned. Security restriction: SELECT read-only statements only."
    )
    public String querySingleRow(
        @ToolParam(name = "dataSourceName", description = "Data source name") String dataSourceName,
        @ToolParam(name = "sql", description = "Standard SQL SELECT statement. If containing dynamic values, must use '?' as placeholder, direct string concatenation is prohibited to prevent SQL injection. Recommended to add LIMIT 1") String sql,
        @ToolParam(name = "params", description = "List of parameter values corresponding to '?' placeholders in SQL, order must match placeholders. Pass empty list [] if no parameters") List<Object> params
    ) {
        // SQL security validation
        String validateError = validateSqlReadOnly(sql);
        if (validateError != null) {
            return ERROR_PREFIX + validateError;
        }

        // Get data source
        DataSource dataSource = getDataSource(dataSourceName);
        if (dataSource == null) {
            return ERROR_PREFIX + "Invalid data source name: '" + dataSourceName + "', available: [" + getAvailableDataSourceNames() + "]";
        }

        try {
            Map<String, Object> result = JdbcQueryUtil.queryOne(dataSource, sql, safeParams(params));
            if (result == null) {
                return "Query result is empty (no matching records)";
            }
            return JSON.toJSONString(result, JSONWriter.Feature.PrettyFormat);
        } catch (SQLException e) {
            System.err.println("[DataTools] SQL execution exception: " + e.getMessage());
            return ERROR_PREFIX + "SQL execution failed: " + e.getMessage() + ". Please check SQL syntax, table name, column name correctness";
        }
    }

    // ========================================================================
    // [Execute Query] querySingleValue - Return single value (String format)
    // ========================================================================

    @ToolDef(
        name = "querySingleValue",
        description = "[Execute Query - Single Value] Execute SQL query and return a single value. Suitable for COUNT statistics, SUM aggregation, AVG average, getting single configuration items, etc. Returns String representation of the value. Expected SQL returns single column, single row. Security restriction: SELECT read-only statements only."
    )
    public String querySingleValue(
        @ToolParam(name = "dataSourceName", description = "Data source name") String dataSourceName,
        @ToolParam(name = "sql", description = "Standard SQL SELECT statement, expected to return single column, single row. If containing dynamic values, must use '?' as placeholder") String sql,
        @ToolParam(name = "params", description = "List of parameter values corresponding to '?' placeholders in SQL, order must match placeholders. Pass empty list [] if no parameters") List<Object> params
    ) {
        // SQL security validation
        String validateError = validateSqlReadOnly(sql);
        if (validateError != null) {
            return ERROR_PREFIX + validateError;
        }

        // Get data source
        DataSource dataSource = getDataSource(dataSourceName);
        if (dataSource == null) {
            return ERROR_PREFIX + "Invalid data source name: '" + dataSourceName + "', available: [" + getAvailableDataSourceNames() + "]";
        }

        try {
            Object result = JdbcQueryUtil.queryValue(dataSource, sql, safeParams(params));
            if (result == null) {
                return "Query result is empty (NULL)";
            }
            // Convert to String uniformly, keep return type consistent
            return result.toString();
        } catch (SQLException e) {
            System.err.println("[DataTools] SQL execution exception: " + e.getMessage());
            return ERROR_PREFIX + "SQL execution failed: " + e.getMessage() + ". Please check SQL syntax, table name, column name correctness";
        }
    }

    // ========================================================================
    // Internal Utility Methods
    // ========================================================================

    /**
     * Find data source (case-insensitive matching)
     */
    private DataSourceInfo findDataSource(String dataSourceName) {
        if (dataSourceInfos.isEmpty()) {
            return null;
        }
        for (DataSourceInfo info : dataSourceInfos) {
            if (info.getName().equalsIgnoreCase(dataSourceName)) {
                return info;
            }
        }
        return null;
    }

    /**
     * Find table (exact matching, case-sensitive)
     */
    private TableInfo findTable(DataSourceInfo dataSource, String tableName) {
        List<TableInfo> tables = dataSource.getTables();
        if (tables == null || tables.isEmpty()) {
            return null;
        }
        for (TableInfo table : tables) {
            if (table.getName().equalsIgnoreCase(tableName)) {
                return table;
            }
        }
        return null;
    }

    /**
     * Safely get DataSource, return null on failure
     */
    private DataSource getDataSource(String dataSourceName) {
        DataSourceInfo info = findDataSource(dataSourceName);
        return info != null ? info.getDataSource() : null;
    }

    /**
     * Get available data source names list (for error messages)
     */
    private String getAvailableDataSourceNames() {
        if (dataSourceInfos.isEmpty()) {
            return "None";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dataSourceInfos.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("'").append(dataSourceInfos.get(i).getName()).append("'");
        }
        return sb.toString();
    }

    /**
     * Get available table names list (for error messages, max 5 returned)
     */
    private String getAvailableTableNames(List<TableInfo> tables) {
        if (tables == null || tables.isEmpty()) return "None";
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (TableInfo table : tables) {
            if (count++ >= 5) {
                sb.append(", ...");
                break;
            }
            if (count > 1) sb.append(", ");
            sb.append("'").append(table.getName()).append("'");
        }
        return sb.toString();
    }

    /**
     * Validate SQL read-only property
     *
     * @return Error message or null (indicating validation passed)
     */
    private String validateSqlReadOnly(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return "SQL statement cannot be empty";
        }
        String upperSql = sql.trim().toUpperCase();

        // Prohibit dangerous operation keywords
        String[] forbidden = {
            "INSERT ", "UPDATE ", "DELETE ", "DROP ", "TRUNCATE ",
            "ALTER ", "CREATE ", "REPLACE ", "GRANT ", "REVOKE ", "EXEC "
        };
        for (String keyword : forbidden) {
            if (upperSql.contains(keyword)) {
                return "Security restriction: " + keyword.trim() + " operation is prohibited, only SELECT query statements are allowed";
            }
        }

        // Must start with SELECT or WITH
        if (!upperSql.startsWith("SELECT") && !upperSql.startsWith("WITH")) {
            return "Security restriction: SQL must start with SELECT or WITH";
        }
        return null;
    }

    /**
     * Safely handle parameter list, avoid null
     */
    private List<Object> safeParams(List<Object> params) {
        return params != null ? params : new ArrayList<>();
    }

    // ========================================================================
    // 🛠️ Formatting Utility Methods
    // ========================================================================

    private String safeStr(String value) {
        return (value != null && !value.trim().isEmpty()) ? value.trim() : "-";
    }

    private String safeNum(Integer value) {
        return value != null ? value.toString() : "-";
    }

    private String formatNullable(Integer nullable) {
        return (nullable != null && nullable == 1) ? "✅" : "❌";
    }

    // ========================================================================
    // Builder Pattern
    // ========================================================================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final List<DataSourceInfo> dataSourceInfos = new ArrayList<>();

        public Builder addDataSourceInfo(DataSourceInfo dataSourceInfo) {
            if (dataSourceInfo != null) {
                this.dataSourceInfos.add(dataSourceInfo);
            }
            return this;
        }

        public Builder addDataSourceInfos(List<DataSourceInfo> dataSourceInfos) {
            if (dataSourceInfos != null) {
                this.dataSourceInfos.addAll(dataSourceInfos);
            }
            return this;
        }

        public List<Tool> buildTools() {
            for (DataSourceInfo dataSourceInfo : this.dataSourceInfos) {
                if (dataSourceInfo instanceof JdbcDataSourceInfo) {
                    ((JdbcDataSourceInfo) dataSourceInfo).buildTables();
                }
            }

            DataTools dataTools = new DataTools(this.dataSourceInfos);
            List<Tool> tools = new ArrayList<>();
            tools.add(dataTools.buildListTablesTool());
            tools.addAll(ToolScanner.scan(dataTools));
            return tools;
        }
    }
}

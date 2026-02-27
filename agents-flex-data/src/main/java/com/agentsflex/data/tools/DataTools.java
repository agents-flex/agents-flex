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
 * DataTools - AI 数据查询工具集
 * <p>
 * 所有 Function Call 方法遵循 "软失败" 原则：
 * - 成功：返回结构化文本（Markdown/JSON）
 * - 失败：返回 "Error: xxx" 格式字符串，让 AI 自主纠错
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
    // 【工具构建】listTables - 返回 Tool 对象（由 ToolScanner 统一处理）
    // ========================================================================

    public Tool buildListTablesTool() {
        StringBuilder sb = new StringBuilder();
        if (!dataSourceInfos.isEmpty()) {
            for (DataSourceInfo dataSourceInfo : dataSourceInfos) {
                sb.append("<data_source>\n")
                    .append("  <name>").append(dataSourceInfo.genName()).append("</name>\n")
                    .append("  <description>").append(
                        dataSourceInfo.genDescription() != null ?
                            dataSourceInfo.getDescription() : "无描述").append("</description>\n")
                    .append("</data_source>\n");
            }
        } else {
            sb.append("<!-- 暂无可用数据源 -->\n");
        }

        String description =
            "【查询流程 - 步骤 1】获取指定数据源下的所有表名列表。\n\n" +
                "## 使用场景：\n" +
                "- 用户要求查询数据，但不确定表名时\n" +
                "- 需要确认某个数据源下有哪些可用的表\n" +
                "- 编写 SQL 前，需要确认目标表是否存在\n\n" +
                "## 重要规则：\n" +
                "- dataSourceName 必须从下方 <available_data_sources> 列表中选择\n" +
                "- 严格匹配名称，区分大小写，不可编造\n" +
                "- 如果用户未指定数据源，先列出可用选项让用户选择\n\n" +
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
                    .description("数据源的名称，必须从 available_data_sources 中获取，不可胡编乱造。")
                    .required(true)
                    .build()
            ).function(argsMap -> {
                String dataSourceName = (String) argsMap.get("dataSourceName");
                if (dataSourceName == null || dataSourceName.isEmpty()) {
                    return ERROR_PREFIX + "当前数据源为空，请指定数据源名称。";
                } else {
                    dataSourceName = dataSourceName.trim();
                }

                for (DataSourceInfo dataSourceInfo : dataSourceInfos) {
                    if (dataSourceInfo.getName().equalsIgnoreCase(dataSourceName)) {
                        // 返回 String 描述，保持风格一致
                        return formatTableList(dataSourceInfo);
                    }
                }
                return ERROR_PREFIX + "未找到数据源: '" + dataSourceName + "'";
            })
            .build();
    }

    /**
     * 格式化表列表为 AI 友好的文本
     */
    private String formatTableList(DataSourceInfo dataSourceInfo) {
        List<TableInfo> tables = dataSourceInfo.getTables();
        if (tables == null || tables.isEmpty()) {
            return "📭 数据源 '" + dataSourceInfo.getName() + "' 下暂无可用表";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📋 数据源: `").append(dataSourceInfo.getName()).append("`\n")
            .append("可用表数量: ").append(tables.size()).append("\n\n");

        sb.append("| 表名 | 描述 |\n")
            .append("|------|------|\n");

        for (TableInfo table : tables) {
            sb.append("| `").append(safeStr(table.genName())).append("`")
                .append(" | ").append(safeStr(table.genDescription()))
                .append(" |\n");
        }

        sb.append("\n> 💡 提示: 调用 listTableColumns 时，tableName 必须使用上述 `表名`（区分大小写）");
        return sb.toString();
    }

    // ========================================================================
    // 【步骤 2】listTableColumns - 获取表结构（返回 Markdown 字符串）
    // ========================================================================

    @ToolDef(
        name = "listTableColumns",
        description = "【步骤 2】获取指定表的字段结构描述。返回 Markdown 格式的表结构文本，包含：字段名、数据类型、长度、是否可空、是否主键、是否自增、字段注释。编写 SQL 前必须调用此工具确认列名。"
    )
    public String listTableColumns(
        @ToolParam(name = "dataSourceName", description = "数据源名称，必须来自 available_data_sources 列表") String dataSourceName,
        @ToolParam(name = "tableName", description = "表名，必须来自 listTables 的返回结果，区分大小写") String tableName
    ) {
        // 🔍 参数校验
        if (dataSourceName == null || dataSourceName.trim().isEmpty()) {
            return ERROR_PREFIX + "dataSourceName 不能为空，请从 available_data_sources 中选择";
        }
        if (tableName == null || tableName.trim().isEmpty()) {
            return ERROR_PREFIX + "tableName 不能为空，请先调用 listTables 获取可用表名";
        }

        // 🔍 查找数据源
        DataSourceInfo targetDataSource = findDataSource(dataSourceName);
        if (targetDataSource == null) {
            return ERROR_PREFIX + "未找到数据源 '" + dataSourceName + "'，可用选项: [" + getAvailableDataSourceNames() + "]";
        }

        // 🔍 查找表
        TableInfo targetTable = findTable(targetDataSource, tableName);
        if (targetTable == null) {
            return ERROR_PREFIX + "表 '" + tableName + "' 不存在，可用表: " + getAvailableTableNames(targetDataSource.getTables());
        }

        // 🔍 获取并格式化字段
        List<ColumnInfo> columns = targetTable.getColumns();
        if (columns == null || columns.isEmpty()) {
            return ERROR_PREFIX + "表 '" + tableName + "' 下没有找到字段定义，可能未正确加载元数据";
        }

        return formatTableSchema(tableName, columns);
    }

    /**
     * 将 ColumnInfo 列表格式化为 AI 友好的 Markdown 表格
     */
    private String formatTableSchema(String tableName, List<ColumnInfo> columns) {
        StringBuilder sb = new StringBuilder();

        sb.append("📊 表结构: `").append(tableName).append("`\n")
            .append("字段总数: ").append(columns.size()).append("\n\n");

        sb.append("| 字段名 | 类型 | 是否为主键 | 是否自增 | 注释 |\n")
            .append("|--------|------|------|------|------|\n");

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

        sb.append("\n> 💡 提示: 编写 SQL 时请使用上述 `字段名`，不能胡编乱造。");
        return sb.toString();
    }

    // ========================================================================
    // 【执行查询】queryDataList - 返回多行结果（JSON 字符串）
    // ========================================================================

    @ToolDef(
        name = "queryDataList",
        description = "【执行查询 - 列表】执行 SQL 查询并返回多行结果。适用于查询列表、多条记录的场景。返回 JSON 数组字符串。安全限制：仅限 SELECT 只读语句，禁止 UPDATE/DELETE/INSERT/DROP 等操作。动态值必须使用 ? 占位符。"
    )
    public String queryDataList(
        @ToolParam(name = "dataSourceName", description = "数据源名称") String dataSourceName,
        @ToolParam(name = "sql", description = "标准的 SQL SELECT 语句。若包含动态值，必须使用 '?' 作为占位符，禁止直接拼接字符串以防 SQL 注入。建议添加合理的 LIMIT 限制返回行数") String sql,
        @ToolParam(name = "params", description = "SQL 中 '?' 占位符对应的参数值列表，顺序需与占位符一致。若无参数则传空列表 []") List<Object> params
    ) {
        // SQL 安全校验
        String validateError = validateSqlReadOnly(sql);
        if (validateError != null) {
            return ERROR_PREFIX + validateError;
        }

        // 获取数据源
        DataSource dataSource = getDataSource(dataSourceName);
        if (dataSource == null) {
            return ERROR_PREFIX + "无效的数据源名称: '" + dataSourceName + "'，可用: [" + getAvailableDataSourceNames() + "]";
        }

        try {
            List<Map<String, Object>> result = JdbcQueryUtil.query(dataSource, sql, safeParams(params));
            // 返回格式化 JSON，pretty=true 便于 AI 解析
            return JSON.toJSONString(result, JSONWriter.Feature.PrettyFormat);
        } catch (SQLException e) {
            // 记录日志（生产环境建议替换为 logger）
            System.err.println("[DataTools] SQL 执行异常: " + e.getMessage());
            return ERROR_PREFIX + "SQL 执行失败: " + e.getMessage() + "。请检查 SQL 语法、表名、列名是否正确";
        }
    }

    // ========================================================================
    // 【执行查询】querySingleRow - 返回单行结果（JSON 字符串）
    // ========================================================================

    @ToolDef(
        name = "querySingleRow",
        description = "【执行查询 - 单行】执行 SQL 查询并返回单行结果。适用于根据 ID 查询详情、获取最新一条记录等场景。返回 JSON 对象字符串。若结果有多行，仅返回第一行。安全限制：仅限 SELECT 只读语句。"
    )
    public String querySingleRow(
        @ToolParam(name = "dataSourceName", description = "数据源名称") String dataSourceName,
        @ToolParam(name = "sql", description = "标准的 SQL SELECT 语句。若包含动态值，必须使用 '?' 作为占位符，禁止直接拼接字符串以防 SQL 注入。建议添加 LIMIT 1") String sql,
        @ToolParam(name = "params", description = "SQL 中 '?' 占位符对应的参数值列表，顺序需与占位符一致。若无参数则传空列表 []") List<Object> params
    ) {
        // SQL 安全校验
        String validateError = validateSqlReadOnly(sql);
        if (validateError != null) {
            return ERROR_PREFIX + validateError;
        }

        // 获取数据源
        DataSource dataSource = getDataSource(dataSourceName);
        if (dataSource == null) {
            return ERROR_PREFIX + "无效的数据源名称: '" + dataSourceName + "'，可用: [" + getAvailableDataSourceNames() + "]";
        }

        try {
            Map<String, Object> result = JdbcQueryUtil.queryOne(dataSource, sql, safeParams(params));
            if (result == null) {
                return "查询结果为空（无匹配记录）";
            }
            return JSON.toJSONString(result, JSONWriter.Feature.PrettyFormat);
        } catch (SQLException e) {
            System.err.println("[DataTools] SQL 执行异常: " + e.getMessage());
            return ERROR_PREFIX + "SQL 执行失败: " + e.getMessage() + "。请检查 SQL 语法、表名、列名是否正确";
        }
    }

    // ========================================================================
    // 【执行查询】querySingleValue - 返回单值（String 格式）
    // ========================================================================

    @ToolDef(
        name = "querySingleValue",
        description = "【执行查询 - 单值】执行 SQL 查询并返回单个值。适用于 COUNT 统计、SUM 求和、AVG 平均、获取单个配置项等场景。返回值的 String 表示。预期 SQL 只返回一列一行。安全限制：仅限 SELECT 只读语句。"
    )
    public String querySingleValue(
        @ToolParam(name = "dataSourceName", description = "数据源名称") String dataSourceName,
        @ToolParam(name = "sql", description = "标准的 SQL SELECT 语句，预期返回单列单行。若包含动态值，必须使用 '?' 作为占位符") String sql,
        @ToolParam(name = "params", description = "SQL 中 '?' 占位符对应的参数值列表，顺序需与占位符一致。若无参数则传空列表 []") List<Object> params
    ) {
        // SQL 安全校验
        String validateError = validateSqlReadOnly(sql);
        if (validateError != null) {
            return ERROR_PREFIX + validateError;
        }

        // 获取数据源
        DataSource dataSource = getDataSource(dataSourceName);
        if (dataSource == null) {
            return ERROR_PREFIX + "无效的数据源名称: '" + dataSourceName + "'，可用: [" + getAvailableDataSourceNames() + "]";
        }

        try {
            Object result = JdbcQueryUtil.queryValue(dataSource, sql, safeParams(params));
            if (result == null) {
                return "查询结果为空（NULL）";
            }
            // 统一转为 String，保持返回类型一致
            return result.toString();
        } catch (SQLException e) {
            System.err.println("[DataTools] SQL 执行异常: " + e.getMessage());
            return ERROR_PREFIX + "SQL 执行失败: " + e.getMessage() + "。请检查 SQL 语法、表名、列名是否正确";
        }
    }

    // ========================================================================
    // 🔧 内部工具方法
    // ========================================================================

    /**
     * 查找数据源（忽略大小写匹配）
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
     * 查找表（精确匹配，区分大小写）
     */
    private TableInfo findTable(DataSourceInfo dataSource, String tableName) {
        List<TableInfo> tables = dataSource.getTables();
        if (tables == null || tables.isEmpty()) {
            return null;
        }
        for (TableInfo table : tables) {
            if (table.getName().equals(tableName)) {
                return table;
            }
        }
        return null;
    }

    /**
     * 安全获取 DataSource，失败返回 null
     */
    private DataSource getDataSource(String dataSourceName) {
        DataSourceInfo info = findDataSource(dataSourceName);
        return info != null ? info.getDataSource() : null;
    }

    /**
     * 获取可用数据源名称列表（用于错误提示）
     */
    private String getAvailableDataSourceNames() {
        if (dataSourceInfos.isEmpty()) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dataSourceInfos.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("'").append(dataSourceInfos.get(i).getName()).append("'");
        }
        return sb.toString();
    }

    /**
     * 获取可用表名列表（用于错误提示，最多返回 5 个）
     */
    private String getAvailableTableNames(List<TableInfo> tables) {
        if (tables == null || tables.isEmpty()) return "无";
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
     * 校验 SQL 只读性
     *
     * @return 错误信息 或 null（表示校验通过）
     */
    private String validateSqlReadOnly(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return "SQL 语句不能为空";
        }
        String upperSql = sql.trim().toUpperCase();

        // 禁止危险操作关键字
        String[] forbidden = {
            "INSERT ", "UPDATE ", "DELETE ", "DROP ", "TRUNCATE ",
            "ALTER ", "CREATE ", "REPLACE ", "GRANT ", "REVOKE ", "EXEC "
        };
        for (String keyword : forbidden) {
            if (upperSql.contains(keyword)) {
                return "安全限制：禁止执行 " + keyword.trim() + " 操作，仅允许 SELECT 查询语句";
            }
        }

        // 必须以 SELECT 或 WITH 开头
        if (!upperSql.startsWith("SELECT") && !upperSql.startsWith("WITH")) {
            return "安全限制：SQL 必须以 SELECT 或 WITH 开头";
        }
        return null;
    }

    /**
     * 安全处理参数列表，避免 null
     */
    private List<Object> safeParams(List<Object> params) {
        return params != null ? params : new ArrayList<>();
    }

    // ========================================================================
    // 🛠️ 格式化工具方法
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
    // 🏗️ Builder 模式
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
               if (dataSourceInfo instanceof JdbcDataSourceInfo){
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

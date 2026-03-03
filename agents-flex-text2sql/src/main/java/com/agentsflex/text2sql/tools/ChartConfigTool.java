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
package com.agentsflex.text2sql.tools;

import com.agentsflex.core.model.chat.tool.annotation.ToolDef;
import com.agentsflex.core.model.chat.tool.annotation.ToolParam;
import com.alibaba.fastjson2.JSON;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ChartConfigGenerator - Universal ECharts Configuration Generator
 * <p>
 * Generate ready-to-render ECharts option from ANY JSON data structure.
 * Data source agnostic: DB results, user input, AI-generated data all supported.
 * </p>
 */
public class ChartConfigTool {

    private static final String ERROR_PREFIX = "Error: ";

    /**
     * Supported chart types
     */
    public enum ChartType {
        AUTO, LINE, BAR, PIE, SCATTER, AREA, RADAR
    }


    @ToolDef(
        name = "suggestChartConfig",
        description =
            "🎯 CHART GENERATOR - THE ONLY VALID WAY TO RENDER CHARTS\n" +
                "========================================================\n\n" +

                "## ⚡ QUICK START (Copy This Output Format EXACTLY):\n" +
                "When user wants a chart, your FINAL response MUST be:\n\n" +
                "```echarts\n" +
                "option = {\n" +
                "  // Your ECharts config here\n" +
                "  xAxis: { type: 'category',  [...] },\n" +
                "  yAxis: { type: 'value' },\n" +
                "  series: [{ type: 'line|bar',  [...] }]\n" +
                "};\n" +
                "```\n\n" +

                "## 🚫 FORBIDDEN OUTPUT PATTERNS (Will Break Frontend):\n" +
                "❌ NEVER wrap echarts config in Markdown image syntax:\n" +
                "   ![echarts option = {...}](data:image/svg+xml;base64,...)  ← WRONG!\n" +
                "❌ NEVER embed base64/SVG/PNG: data:image/... ← WRONG!\n" +
                "❌ NEVER put config inside alt text, comments, or code fences with image URL\n" +
                "❌ NEVER return only text description without ```echarts code block\n\n" +

                "## ✅ CORRECT vs INCORRECT Examples:\n" +
                "### ❌ WRONG (Common Mistakes):\n" +
                "```markdown\n" +
                "![user growth chart](data:image/svg+xml;base64,PHN2Zy4uLg==)\n" +
                "```\n" +
                "```markdown\n" +
                "![echarts option = {...}](image/svg...)\n" +
                "```\n" +
                "```text\n" +
                "Here is the chart config: {xAxis: {...}}  ← Missing ```echarts fence!\n" +
                "```\n\n" +

                "### ✅ CORRECT (Copy This):\n" +
                "\n" +
                "(some title)\n\n" +
                "```echarts\n" +
                "option = {\n" +
                "  title: { text: '(chart title)', left: 'center' },\n" +
                "  tooltip: { trigger: 'axis' },\n" +
                "  xAxis: { type: 'category',  ['2025-06', '2025-12'] },\n" +
                "  yAxis: { type: 'value' },\n" +
                "  series: [{ type: 'line',  [2, 2, 1, 2] }]\n" +
                "};\n" +
                "```\n" +
                "(some description or analyze)\n\n" +

                "## 🎯 When to Call This Tool:\n" +
                "- ✅ User asks for: chart/graph/visualization/plot/trend\n" +
                "- ✅ You have JSON data array and need visual display\n" +
                "- ✅ You want to show: trends, comparisons, distributions, rankings\n\n" +

                "## 📥 Input Data Format:\n" +
                "```json\n" +
                "[\n" +
                "  {\"date\":\"2024-01\",\"users\":120,\"revenue\":5000},\n" +
                "  {\"date\":\"2024-02\",\"users\":145,\"revenue\":6200}\n" +
                "]\n" +
                "```\n\n" +

                "## 📊 Chart Type Selection Guide:\n" +
                "| Type   | Best For                  | Required Fields      | Example Use Case     |\n" +
                "|--------|---------------------------|---------------------|---------------------|\n" +
                "| line   | Time series, trends       | 1 time + 1+ numeric | Monthly user growth |\n" +
                "| bar    | Category comparison       | 1 category + 1+ num | Dept budget compare |\n" +
                "| pie    | Proportion (≤8 items)     | 1 name + 1 numeric  | Status distribution |\n" +
                "| area   | Cumulative trends         | Same as line        | Revenue accumulation|\n" +
                "| scatter| Correlation analysis      | 2 numeric fields    | Price vs Volume     |\n" +
                "| auto   | Let tool auto-decide      | Any valid structure | When unsure         |\n\n" +

                "## 🔧 Optional Parameters:\n" +
                "- xField: Field name for x-axis (auto-detected if empty)\n" +
                "- yFields: Field name(s) for y-axis, e.g. \"sales\" or [\"sales\",\"profit\"]\n" +
                "- title: Chart title string (auto-generated if empty)\n" +
                "- config: Advanced ECharts overrides, e.g. {\"legend\":{\"show\":true}}\n\n" +

                "## 🧠 BEFORE RETURNING - Self-Check Checklist:\n" +
                "□ Does my response start with optional text, then ```echarts code block?\n" +
                "□ Does the code block contain 'option = {' followed by valid JS object?\n" +
                "□ Did I AVOID any Markdown image syntax ![...](...)?\n" +
                "□ Did I AVOID any data:image/ or base64 strings?\n" +
                "□ If I'm unsure, did I use chartType=\"auto\" and let the tool decide?\n\n" +

                "## ⚠️ CRITICAL: Format = Functionality\n" +
                "If you return ANY format other than ```echarts option = {...}; ```,\n" +
                "the frontend parser will FAIL and users will see broken charts.\n" +
                "Your job is NOT done until the output matches the ✅ CORRECT example above."
    )
    public String suggestChartConfig(
        @ToolParam(name = "data",
            description = "📦 Raw data array: [{field1: value1, field2: value2}]. Can be DB results, user input, or AI-generated data.")
        List<Map<String, Object>> data,

        @ToolParam(name = "chartType",
            description = "🎨 Preferred chart type: 'auto' | 'line' | 'bar' | 'pie' | 'scatter' | 'area'. Use 'auto' for intelligent recommendation.")
        String chartType,

        @ToolParam(name = "xField",
            description = "📍 Optional: Field name for x-axis/category. Auto-detected if empty.")
        String xField,

        @ToolParam(name = "yFields",
            description = "📈 Optional: Field name(s) for y-axis/values. Auto-detected if empty. For multi-series, pass array like ['sales','profit'].")
        Object yFields,  // String or List<String>

        @ToolParam(name = "title",
            description = "🏷️ Optional: Chart title. Auto-generated from data if empty.")
        String title,

        @ToolParam(name = "config",
            description = "⚙️ Optional: Advanced ECharts config overrides. JSON object like {\"tooltip\":{\"formatter\":\"{b}: {c}\"}, \"legend\":{\"show\":true}}")
        Map<String, Object> config
    ) {
        try {
            // 1. 参数校验
            if (data == null || data.isEmpty()) {
                return ERROR_PREFIX + "Data array cannot be empty. Please provide at least one data record.";
            }

            // 2. 解析参数
            ChartType type = parseChartType(chartType);
            List<String> yFieldList = parseYFields(yFields);

            // 3. 分析数据特征
            DataProfile profile = analyzeDataProfile(data, xField, yFieldList);
            if (profile.getError() != null) {
                return ERROR_PREFIX + profile.getError();
            }

            // 4. 智能推荐图表类型（如果为 AUTO）
            if (type == ChartType.AUTO) {
                type = recommendChartType(profile);
            }

            // 5. 生成 ECharts 配置
            String echartsOption = generateEChartsOption(data, profile, type, title, config);

            // 6. 返回标准代码块
            return "```echarts\noption = " + echartsOption + ";\n```";

        } catch (Exception e) {
            System.err.println("[ChartConfigGenerator] Exception: " + e.getMessage());
            return ERROR_PREFIX + "Failed to generate chart config: " + e.getMessage();
        }
    }

    // ========== 内部核心逻辑 ==========

    /**
     * 解析图表类型枚举
     */
    private ChartType parseChartType(String typeStr) {
        if (typeStr == null || typeStr.trim().isEmpty()) return ChartType.AUTO;
        try {
            return ChartType.valueOf(typeStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ChartType.AUTO; // fallback
        }
    }

    /**
     * 解析 yFields 参数（支持 String 或 List）
     */
    @SuppressWarnings("unchecked")
    private List<String> parseYFields(Object yFields) {
        if (yFields == null) return new ArrayList<>();
        if (yFields instanceof String) {
            String s = (String) yFields;
            return s.isEmpty() ? new ArrayList<>() : Collections.singletonList(s);
        }
        if (yFields instanceof List) {
            List<String> result = new ArrayList<>();
            for (Object item : (List<?>) yFields) {
                if (item instanceof String && !((String) item).trim().isEmpty()) {
                    result.add((String) item);
                }
            }
            return result;
        }
        return new ArrayList<>();
    }

    /**
     * 分析数据特征，为图表生成提供元信息
     */
    private DataProfile analyzeDataProfile(List<Map<String, Object>> data,
                                           String preferredX, List<String> preferredY) {
        DataProfile profile = new DataProfile();
        Map<String, Object> firstRow = data.get(0);
        Set<String> keys = firstRow.keySet();

        // 1. 推断每列的类型
        Map<String, ColumnType> columnTypes = new HashMap<>();
        for (String key : keys) {
            columnTypes.put(key, inferColumnType(data, key));
        }
        profile.setColumnTypes(columnTypes);

        // 2. 自动选择 xField
        String xField = autoSelectXField(keys, columnTypes, preferredX);
        if (xField == null) {
            profile.setError("Cannot auto-detect x-axis field. Please specify 'xField' parameter.");
            return profile;
        }
        profile.setXField(xField);

        // 3. 自动选择 yFields
        List<String> yFields = autoSelectYFields(keys, columnTypes, xField, preferredY);
        if (yFields.isEmpty()) {
            profile.setError("Cannot auto-detect numeric field(s) for y-axis. Please specify 'yFields' parameter.");
            return profile;
        }
        profile.setYFields(yFields);

        // 4. 检测是否为时间序列
        profile.setTimeSeries(columnTypes.get(xField) == ColumnType.TIME);

        // 5. 检测数据量
        profile.setRecordCount(data.size());

        return profile;
    }

    /**
     * 推断列的数据类型
     */
    private ColumnType inferColumnType(List<Map<String, Object>> data, String fieldName) {
        // 采样前 10 行非空值进行推断
        int sampleCount = 0;
        boolean hasNumber = false, hasTime = false, hasString = false;

        for (int i = 0; i < Math.min(data.size(), 10) && sampleCount < 5; i++) {
            Object val = data.get(i).get(fieldName);
            if (val == null) continue;
            sampleCount++;

            if (val instanceof Number) {
                hasNumber = true;
            } else if (val instanceof String) {
                String s = ((String) val).trim();
                // 时间格式检测
                if (s.matches("\\d{4}-\\d{2}-\\d{2}.*") || s.matches("\\d{2}:\\d{2}.*")) {
                    hasTime = true;
                } else {
                    hasString = true;
                }
            } else if (val instanceof Boolean) {
                hasString = true; // boolean 作为分类处理
            }
        }

        if (hasTime) return ColumnType.TIME;
        if (hasNumber && !hasString) return ColumnType.NUMBER;
        return ColumnType.STRING;
    }

    /**
     * 自动选择 x 轴字段
     */
    private String autoSelectXField(Set<String> keys, Map<String, ColumnType> types, String preferred) {
        if (preferred != null && keys.contains(preferred)) return preferred;

        // 优先级: 时间字段 > 字符串字段 > 第一个字段
        for (String key : keys) {
            if (types.get(key) == ColumnType.TIME) return key;
        }
        for (String key : keys) {
            if (types.get(key) == ColumnType.STRING) return key;
        }
        return keys.iterator().next(); // fallback
    }

    /**
     * 自动选择 y 轴字段（数值型）
     */
    private List<String> autoSelectYFields(Set<String> keys, Map<String, ColumnType> types,
                                           String xField, List<String> preferred) {
        if (preferred != null && !preferred.isEmpty()) {
            // 过滤掉不存在的字段和 xField
            return preferred.stream()
                .filter(keys::contains)
                .filter(f -> !f.equals(xField))
                .filter(f -> types.get(f) == ColumnType.NUMBER)
                .collect(Collectors.toList());
        }

        // 自动选择所有数值型字段（排除 xField）
        return keys.stream()
            .filter(k -> !k.equals(xField))
            .filter(k -> types.get(k) == ColumnType.NUMBER)
            .limit(3) // 最多 3 个系列，避免图表过杂
            .collect(Collectors.toList());
    }

    /**
     * 智能推荐图表类型
     */
    private ChartType recommendChartType(DataProfile profile) {
        if (profile.isTimeSeries()) {
            return profile.getRecordCount() > 20 ? ChartType.LINE : ChartType.AREA;
        }

        if (profile.getYFields().size() == 1 && profile.getRecordCount() <= 10) {
            // 少分类单数值 → 饼图候选
            if (profile.getColumnTypes().get(profile.getXField()) == ColumnType.STRING) {
                return ChartType.PIE;
            }
        }

        if (profile.getYFields().size() >= 2) {
            return ChartType.BAR; // 多系列默认柱状
        }

        return ChartType.BAR; // 默认
    }

    /**
     * 生成 ECharts 配置（核心模板引擎）
     */
    private String generateEChartsOption(List<Map<String, Object>> data, DataProfile profile,
                                         ChartType chartType, String title, Map<String, Object> config) {
        StringBuilder sb = new StringBuilder("{\n");

        // 1. 基础配置
        if (title != null && !title.isEmpty()) {
            sb.append("  title: { text: '").append(escapeJs(title)).append("', left: 'center' },\n");
        }
        sb.append("  tooltip: { trigger: '").append(chartType == ChartType.PIE ? "item" : "axis").append("' },\n");

        // 2. 图例（多系列时显示）
        if (profile.getYFields().size() > 1) {
            sb.append("  legend: { data: [")
                .append(profile.getYFields().stream()
                    .map(f -> "'" + escapeJs(f) + "'")
                    .collect(Collectors.joining(", ")))
                .append("] },\n");
        }

        // 3. 坐标轴
        if (chartType != ChartType.PIE) {
            sb.append("  xAxis: { type: '").append(profile.isTimeSeries() ? "time" : "category").append("', ");
            if (!profile.isTimeSeries()) {
                sb.append("data: ").append(JSON.toJSONString(extractValues(data, profile.getXField())));
            }
            sb.append(" },\n");
            sb.append("  yAxis: { type: 'value' },\n");
        }

        // 4. 系列数据
        sb.append("  series: [");
        if (chartType == ChartType.PIE) {
            // 饼图特殊处理
            sb.append(generatePieSeries(data, profile));
        } else {
            // line/bar/scatter/area
            for (int i = 0; i < profile.getYFields().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(generateCartesianSeries(profile.getYFields().get(i), data, profile, chartType));
            }
        }
        sb.append("]\n");

        // 5. 用户自定义配置覆盖
        if (config != null && !config.isEmpty()) {
            String userConfig = JSON.toJSONString(config)
                .replaceAll("^\\{|\\}$", "")  // 去掉外层 {}
                .replaceAll("^,", "");         // 去掉前导逗号
            if (!userConfig.trim().isEmpty()) {
                sb.append(", ").append(userConfig);
            }
        }

        sb.append("}");
        return sb.toString();
    }

    /**
     * 生成饼图系列配置
     */
    private String generatePieSeries(List<Map<String, Object>> data, DataProfile profile) {
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("    type: 'pie',\n");
        sb.append("    radius: '60%',\n");
        sb.append("    data: [");

        for (int i = 0; i < data.size(); i++) {
            if (i > 0) sb.append(", ");
            Map<String, Object> row = data.get(i);
            Object name = row.get(profile.getXField());
            Object value = row.get(profile.getYFields().get(0));
            sb.append("{name: '").append(escapeJs(String.valueOf(name)))
                .append("', value: ").append(value).append("}");
        }
        sb.append("]\n  }");
        return sb.toString();
    }

    /**
     * 生成直角坐标系系列配置
     */
    private String generateCartesianSeries(String yField, List<Map<String, Object>> data,
                                           DataProfile profile, ChartType chartType) {
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("    name: '").append(escapeJs(yField)).append("',\n");

        // 图表类型映射
        String echartsType = chartType != null ? chartType.name().toLowerCase() : "bar";

        sb.append("    type: '").append(echartsType).append("',\n");

        // area 特殊处理
        if (chartType == ChartType.AREA) {
            sb.append("    areaStyle: {},\n");
        }

        // 数据
        List<Object> values = extractValues(data, yField);
        sb.append("    data: ").append(JSON.toJSONString(values)).append("\n");
        sb.append("  }");
        return sb.toString();
    }

    /**
     * 提取某列的值列表
     */
    private List<Object> extractValues(List<Map<String, Object>> data, String fieldName) {
        return data.stream()
            .map(row -> row.get(fieldName))
            .collect(Collectors.toList());
    }

    /**
     * JS 字符串转义（防止 XSS/语法错误）
     */
    private String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r");
    }

    // ========== 辅助类 ==========

    private enum ColumnType {STRING, NUMBER, TIME, BOOLEAN}

    private static class DataProfile {
        private String xField;
        private List<String> yFields = new ArrayList<>();
        private Map<String, ColumnType> columnTypes = new HashMap<>();
        private boolean timeSeries;
        private int recordCount;
        private String error;

        // Getters/Setters...
        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }

        public String getXField() {
            return xField;
        }

        public void setXField(String xField) {
            this.xField = xField;
        }

        public List<String> getYFields() {
            return yFields;
        }

        public void setYFields(List<String> yFields) {
            this.yFields = yFields;
        }

        public Map<String, ColumnType> getColumnTypes() {
            return columnTypes;
        }

        public void setColumnTypes(Map<String, ColumnType> columnTypes) {
            this.columnTypes = columnTypes;
        }

        public boolean isTimeSeries() {
            return timeSeries;
        }

        public void setTimeSeries(boolean timeSeries) {
            this.timeSeries = timeSeries;
        }

        public int getRecordCount() {
            return recordCount;
        }

        public void setRecordCount(int recordCount) {
            this.recordCount = recordCount;
        }
    }
}

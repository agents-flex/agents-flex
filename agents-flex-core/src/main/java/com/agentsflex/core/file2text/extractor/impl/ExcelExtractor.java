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
package com.agentsflex.core.file2text.extractor.impl;

import com.agentsflex.core.file2text.extractor.FileExtractor;
import com.agentsflex.core.file2text.source.DocumentSource;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Excel 文档提取器（.xlsx, .xlsm, .xls, .xltx）
 * 输出格式：Markdown 表格，支持多 Sheet、公式计算、特殊字符转义
 */
public class ExcelExtractor implements FileExtractor {

    private static final Set<String> KNOWN_MIME_TYPES;
    private static final String MIME_PREFIX = "application/vnd.openxmlformats-officedocument.spreadsheetml";
    private static final Set<String> SUPPORTED_EXTENSIONS;

    static {
        Set<String> mimeTypes = new HashSet<>();
        mimeTypes.add("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        mimeTypes.add("application/vnd.openxmlformats-officedocument.spreadsheetml.template");
        mimeTypes.add("application/vnd.ms-excel");
        mimeTypes.add("application/vnd.ms-excel.sheet.macroEnabled.12");
        KNOWN_MIME_TYPES = Collections.unmodifiableSet(mimeTypes);

        Set<String> extensions = new HashSet<>();
        extensions.add("xlsx");
        extensions.add("xlsm");
        extensions.add("xls");
        extensions.add("xltx");
        SUPPORTED_EXTENSIONS = Collections.unmodifiableSet(extensions);
    }

    @Override
    public boolean supports(DocumentSource source) {
        String mimeType = source.getMimeType();
        String fileName = source.getFileName();

        if (mimeType != null && KNOWN_MIME_TYPES.contains(mimeType)) {
            return true;
        }
        if (mimeType != null && mimeType.startsWith(MIME_PREFIX)) {
            return true;
        }
        if (fileName != null) {
            String ext = getExtension(fileName);
            if (ext != null && SUPPORTED_EXTENSIONS.contains(ext.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String extractText(DocumentSource source) throws IOException {
        StringBuilder text = new StringBuilder();

        try (InputStream is = source.openStream();
             Workbook workbook = openWorkbook(is, source.getFileName())) {

            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            int sheetCount = workbook.getNumberOfSheets();

            for (int i = 0; i < sheetCount; i++) {
                Sheet sheet = workbook.getSheetAt(i);
                String sheetName = sheet.getSheetName();

                // Sheet 标题（Markdown 三级标题）
                text.append("\n### ").append(escapeMarkdown(sheetName)).append("\n\n");

                // 收集所有非空行数据，用于计算最大列数
                java.util.List<java.util.List<String>> rowsData = new java.util.ArrayList<>();
                int maxColumns = 0;

                for (Row row : sheet) {
                    if (isRowEmpty(row)) {
                        continue;
                    }
                    java.util.List<String> rowData = new java.util.ArrayList<>();
                    short firstCellNum = row.getFirstCellNum();
                    short lastCellNum = row.getLastCellNum();

                    for (short j = firstCellNum; j < lastCellNum; j++) {
                        Cell cell = row.getCell(j);
                        String cellValue = getCellValue(cell, evaluator);
                        rowData.add(cellValue != null ? cellValue : "");
                    }
                    if (!rowData.isEmpty()) {
                        rowsData.add(rowData);
                        maxColumns = Math.max(maxColumns, rowData.size());
                    }
                }

                if (rowsData.isEmpty()) {
                    text.append("*Empty Sheet*\n");
                    continue;
                }

                // 输出 Markdown 表格
                outputMarkdownTable(text, rowsData, maxColumns);
            }

        } catch (Exception e) {
            throw new IOException("Failed to extract Excel: " + e.getMessage(), e);
        }

        return text.toString().trim();
    }

    /**
     * 输出 Markdown 格式表格
     */
    private void outputMarkdownTable(StringBuilder output,
                                     java.util.List<java.util.List<String>> rowsData,
                                     int maxColumns) {
        // 输出表头行（第一行作为表头）
        java.util.List<String> headerRow = rowsData.get(0);
        output.append("| ");
        for (int i = 0; i < maxColumns; i++) {
            String cell = i < headerRow.size() ? escapeMarkdownCell(headerRow.get(i)) : "";
            output.append(cell).append(" | ");
        }
        output.append("\n| ");

        // 输出分隔行（默认左对齐 :---）
        for (int i = 0; i < maxColumns; i++) {
            output.append(":--- | ");
        }
        output.append("\n");

        // 输出数据行
        for (int r = 1; r < rowsData.size(); r++) {
            java.util.List<String> rowData = rowsData.get(r);
            output.append("| ");
            for (int i = 0; i < maxColumns; i++) {
                String cell = i < rowData.size() ? escapeMarkdownCell(rowData.get(i)) : "";
                output.append(cell).append(" | ");
            }
            output.append("\n");
        }
        output.append("\n");
    }

    /**
     * 转义单元格内容中的 Markdown 特殊字符
     * 主要处理：| \ ` * _ [ ] < > 等，避免破坏表格结构
     */
    private String escapeMarkdownCell(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        // 先转义反斜杠，避免重复转义
        String escaped = content.replace("\\", "\\\\");
        // 转义管道符（表格分隔符）
        escaped = escaped.replace("|", "\\|");
        // 转义其他可能的 Markdown 语法字符（可选，根据需求开启）
        // escaped = escaped.replace("*", "\\*").replace("_", "\\_").replace("`", "\\`");
        // 移除换行符，避免破坏表格行结构
        escaped = escaped.replace("\n", " ").replace("\r", " ");
        return escaped.trim();
    }

    /**
     * 转义 Sheet 名称中的 Markdown 特殊字符（用于标题）
     */
    private String escapeMarkdown(String text) {
        if (text == null) return "";
        return text.replace("#", "\\#").replace("*", "\\*");
    }

    private Workbook openWorkbook(InputStream inputStream, String fileName) throws IOException {
        String ext = fileName != null ? getExtension(fileName) : null;

        if ("xls".equalsIgnoreCase(ext)) {
            return new HSSFWorkbook(inputStream);
        } else if ("xlsx".equalsIgnoreCase(ext) || "xlsm".equalsIgnoreCase(ext) || "xltx".equalsIgnoreCase(ext)) {
            return new XSSFWorkbook(inputStream);
        }

        try {
            return new XSSFWorkbook(inputStream);
        } catch (Exception e) {
            throw new IOException("Unable to determine Excel format. Please ensure file extension is correct.", e);
        }
    }

    private String getCellValue(Cell cell, FormulaEvaluator evaluator) {
        if (cell == null) {
            return "";
        }
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                }
                double numericValue = cell.getNumericCellValue();
                if (numericValue == Math.floor(numericValue) && !Double.isInfinite(numericValue)) {
                    return String.valueOf((long) numericValue);
                }
                return String.valueOf(numericValue);
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    CellValue evaluated = evaluator.evaluate(cell);
                    return formatEvaluatedValue(evaluated);
                } catch (Exception e) {
                    return cell.getCellFormula();
                }
            case BLANK:
            case _NONE:
            default:
                return "";
        }
    }

    private String formatEvaluatedValue(CellValue evaluated) {
        if (evaluated == null) {
            return "";
        }
        switch (evaluated.getCellType()) {
            case STRING:
                return evaluated.getStringValue();
            case NUMERIC:
                double num = evaluated.getNumberValue();
                if (num == Math.floor(num) && !Double.isInfinite(num)) {
                    return String.valueOf((long) num);
                }
                return String.valueOf(num);
            case BOOLEAN:
                return String.valueOf(evaluated.getBooleanValue());
            case ERROR:
                return "#ERROR:" + evaluated.getErrorValue();
            default:
                return "";
        }
    }

    private boolean isRowEmpty(Row row) {
        if (row == null) return true;
        for (Cell cell : row) {
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                // 空行判断使用简化逻辑，不传 evaluator
                String value;
                if (cell.getCellType() == CellType.STRING) {
                    value = cell.getStringCellValue();
                } else {
                    value = String.valueOf(cell);
                }
                if (value != null && !value.trim().isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public int getOrder() {
        return 10;
    }

}

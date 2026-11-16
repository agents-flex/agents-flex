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
package com.agentsflex.doc.extractor.impl;

import com.agentsflex.core.document.DocumentImagePublisher;
import com.agentsflex.doc.extractor.StructuredDocumentExtractor;
import com.agentsflex.doc.model.ExtractedDocument;
import com.agentsflex.doc.model.TableBlock;
import com.agentsflex.doc.model.TableCell;
import com.agentsflex.doc.model.TableRow;
import com.agentsflex.doc.model.SectionBlock;
import com.agentsflex.doc.model.SectionType;
import com.agentsflex.doc.model.ParagraphBlock;
import com.agentsflex.doc.source.DocumentSource;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.util.CellRangeAddress;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Excel 文档提取器（.xlsx, .xlsm, .xls, .xlt, .xltx, .xltm）
 * 输出格式：Markdown 表格，支持多 Sheet、公式计算、特殊字符转义
 */
public class ExcelExtractor implements StructuredDocumentExtractor {

    /**
     * Excel 格式已知的精确 MIME 类型集合。
     */
    private static final Set<String> KNOWN_MIME_TYPES;

    /**
     * 用于匹配 Open XML Excel MIME 变体的前缀。
     */
    private static final String MIME_PREFIX = "application/vnd.openxmlformats-officedocument.spreadsheetml";

    /**
     * 支持的工作簿和模板文件扩展名。
     */
    private static final Set<String> SUPPORTED_EXTENSIONS;

    static {
        Set<String> mimeTypes = new HashSet<>();
        mimeTypes.add("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        mimeTypes.add("application/vnd.openxmlformats-officedocument.spreadsheetml.template");
        mimeTypes.add("application/vnd.ms-excel");
        mimeTypes.add("application/vnd.ms-excel.sheet.macroenabled.12");
        mimeTypes.add("application/vnd.ms-excel.template.macroenabled.12");
        KNOWN_MIME_TYPES = Collections.unmodifiableSet(mimeTypes);

        Set<String> extensions = new HashSet<>();
        extensions.add("xlsx");
        extensions.add("xlsm");
        extensions.add("xls");
        extensions.add("xlt");
        extensions.add("xltx");
        extensions.add("xltm");
        SUPPORTED_EXTENSIONS = Collections.unmodifiableSet(extensions);
    }

    /**
     * 判断输入源是否属于受支持的 Excel 工作簿或模板格式。
     *
     * @param source 文档输入源
     * @return 支持该输入源时返回 {@code true}
     */
    @Override
    public boolean supports(DocumentSource source) {
        String mimeType = source.getMimeType();
        String fileName = source.getFileName();
        String normalizedMimeType = mimeType != null
            ? mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT)
            : null;

        if (normalizedMimeType != null && KNOWN_MIME_TYPES.contains(normalizedMimeType)) {
            return true;
        }
        if (normalizedMimeType != null && normalizedMimeType.startsWith(MIME_PREFIX)) {
            return true;
        }
        if (fileName != null) {
            String ext = getExtension(fileName);
            if (ext != null && SUPPORTED_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将 Excel 工作簿解析为结构化文档。
     *
     * <p>每个工作表先输出三级标题，再输出一个结构化表格。公式使用 POI 计算器求值，
     * 合并区域通过工作表的 merged regions 保留为 {@code rowspan}/{@code colspan}。</p>
     *
     * @param source                 Excel 文档输入源
     * @param documentImagePublisher 图片发布器；当前 Excel 解析不处理图片
     * @return 结构化工作簿内容
     * @throws IOException 工作簿读取或解析失败
     */
    @Override
    public ExtractedDocument extractDocument(DocumentSource source,
                                             DocumentImagePublisher documentImagePublisher) throws IOException {
        ExtractedDocument document = new ExtractedDocument();
        try (InputStream is = source.openStream();
             Workbook workbook = openWorkbook(is, source.getFileName())) {

            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            int sheetCount = workbook.getNumberOfSheets();

            for (int i = 0; i < sheetCount; i++) {
                Sheet sheet = workbook.getSheetAt(i);
                String sheetName = sheet.getSheetName();
                SectionBlock section = new SectionBlock(SectionType.SHEET, sheetName);
                TableBlock table = extractSheet(sheet, evaluator);
                if (table.isEmpty()) section.addBlock(new ParagraphBlock("*Empty Sheet*"));
                else section.addBlock(table);
                document.addBlock(section);
            }

        } catch (Exception e) {
            throw new IOException("Failed to extract Excel: " + e.getMessage(), e);
        }

        return document;
    }

    /**
     * 将单个工作表转换为结构化表格。
     *
     * <p>表格边界同时考虑有值单元格和合并区域，因此合并区域中的空行、空列不会被错误删除。
     * 合并区域只在左上角创建锚点单元格，其余覆盖位置跳过。</p>
     *
     * @param sheet     Excel 工作表
     * @param evaluator 公式计算器
     * @return 结构化表格；工作表无有效内容时返回空表格
     */
    private TableBlock extractSheet(Sheet sheet, FormulaEvaluator evaluator) {
        TableBlock table = new TableBlock();
        int minRow = Integer.MAX_VALUE;
        int maxRow = -1;
        int minColumn = Integer.MAX_VALUE;
        int maxColumn = -1;

        for (Row row : sheet) {
            for (Cell cell : row) {
                if (!getCellValue(cell, evaluator).trim().isEmpty()) {
                    minRow = Math.min(minRow, row.getRowNum());
                    maxRow = Math.max(maxRow, row.getRowNum());
                    minColumn = Math.min(minColumn, cell.getColumnIndex());
                    maxColumn = Math.max(maxColumn, cell.getColumnIndex());
                }
            }
        }
        for (CellRangeAddress region : sheet.getMergedRegions()) {
            minRow = Math.min(minRow, region.getFirstRow());
            maxRow = Math.max(maxRow, region.getLastRow());
            minColumn = Math.min(minColumn, region.getFirstColumn());
            maxColumn = Math.max(maxColumn, region.getLastColumn());
        }
        if (maxRow < 0 || maxColumn < 0) return table;

        for (int rowIndex = minRow; rowIndex <= maxRow; rowIndex++) {
            TableRow tableRow = new TableRow();
            Row row = sheet.getRow(rowIndex);
            for (int columnIndex = minColumn; columnIndex <= maxColumn; columnIndex++) {
                CellRangeAddress merged = findMergedRegion(sheet, rowIndex, columnIndex);
                if (merged != null && (rowIndex != merged.getFirstRow()
                    || columnIndex != merged.getFirstColumn())) continue;
                Cell cell = row == null ? null : row.getCell(columnIndex);
                int rowSpan = merged == null ? 1 : merged.getNumberOfCells()
                    / (merged.getLastColumn() - merged.getFirstColumn() + 1);
                int colSpan = merged == null ? 1
                    : merged.getLastColumn() - merged.getFirstColumn() + 1;
                tableRow.addCell(new TableCell(getCellValue(cell, evaluator),
                    columnIndex - minColumn, rowSpan, colSpan, false));
            }
            table.addRow(tableRow);
        }
        return table;
    }

    /**
     * 查找覆盖指定坐标的合并区域。
     *
     * @param sheet  Excel 工作表
     * @param row    零基行索引
     * @param column 零基列索引
     * @return 覆盖该坐标的合并区域；不存在时返回 {@code null}
     */
    private CellRangeAddress findMergedRegion(Sheet sheet, int row, int column) {
        for (CellRangeAddress region : sheet.getMergedRegions()) {
            if (region.isInRange(row, column)) return region;
        }
        return null;
    }

    /**
     * 根据文件扩展名打开对应类型的 POI 工作簿。
     *
     * @param inputStream 工作簿输入流
     * @param fileName    原始文件名
     * @return 已打开的工作簿
     * @throws IOException 文件格式无法识别或工作簿损坏
     */
    private Workbook openWorkbook(InputStream inputStream, String fileName) throws IOException {
        String ext = fileName != null ? getExtension(fileName) : null;

        if ("xls".equalsIgnoreCase(ext) || "xlt".equalsIgnoreCase(ext)) {
            return new HSSFWorkbook(inputStream);
        } else if ("xlsx".equalsIgnoreCase(ext) || "xlsm".equalsIgnoreCase(ext)
            || "xltx".equalsIgnoreCase(ext) || "xltm".equalsIgnoreCase(ext)) {
            return new XSSFWorkbook(inputStream);
        }

        try {
            return new XSSFWorkbook(inputStream);
        } catch (Exception e) {
            throw new IOException("Unable to determine Excel format. Please ensure file extension is correct.", e);
        }
    }

    /**
     * 读取单元格的显示值。
     *
     * <p>公式单元格优先返回计算结果；计算失败时回退为公式表达式。整数数值会去除无意义的小数部分。</p>
     *
     * @param cell      Excel 单元格，可为 {@code null}
     * @param evaluator 公式计算器
     * @return 非 {@code null} 的文本值
     */
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

    /**
     * 将 POI 公式计算结果转换为文本。
     *
     * @param evaluated 公式计算结果
     * @return 非 {@code null} 的文本值
     */
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

    /**
     * 返回解析器优先级。
     *
     * @return 优先级 10，确保专用 Excel 解析器优先于通用解析器
     */
    @Override
    public int getOrder() {
        return 10;
    }

}

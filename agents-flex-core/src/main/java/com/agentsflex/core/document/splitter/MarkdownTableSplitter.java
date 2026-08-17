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
package com.agentsflex.core.document.splitter;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.document.DocumentSplitter;
import com.agentsflex.core.document.id.DocumentIdGenerator;
import com.agentsflex.core.util.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Markdown 长表格分割器。
 *
 * <p>该分割器用于处理包含 GFM（GitHub Flavored Markdown）表格的文档。它先识别
 * “表头行 + 分隔行 + 数据行”结构，再将字符数超过 {@link #maxChunkSize} 的表格按
 * 完整数据行拆成多个 {@link Document}。每个表格分段都会重复原始表头和分隔行，
 * 因此任意一个分段都仍是合法且可独立理解的 Markdown 表格。</p>
 *
 * <p>主要行为如下：</p>
 * <ul>
 *     <li>只拆分超过长度限制的表格，短表格与普通正文保持原样；</li>
 *     <li>正文、表格和后续正文按照原文顺序输出；</li>
 *     <li>始终以完整数据行为最小单位，不会从单元格中间截断内容；</li>
 *     <li>可通过 {@link #overlapRows} 在相邻分段间重复若干数据行；</li>
 *     <li>忽略反引号或波浪号围栏代码块中的表格语法；</li>
 *     <li>保留源文档元数据，并为表格分段补充表格索引和分段索引元数据。</li>
 * </ul>
 *
 * <p>长度使用 {@link String#length()} 计算，并包含表头、分隔行及换行符。如果表头
 * 或单个数据行本身已经超过限制，为保证 Markdown 结构和单元格内容完整，该分段会
 * 允许超过 {@code maxChunkSize}。本实现面向一行表示一条记录的标准 Markdown 表格，
 * 不负责解析 HTML 表格或跨行单元格。</p>
 */
public class MarkdownTableSplitter implements DocumentSplitter {

    /**
     * 单个表格分段允许的目标最大字符数。
     *
     * <p>这是尽量满足的上限，而不是强制截断线。完整数据行优先级更高，因此表头或
     * 单行过长时，最终分段可能超过该值。</p>
     */
    private int maxChunkSize;

    /**
     * 相邻表格分段之间期望重复的数据行数，不包含表头和分隔行。
     *
     * <p>如果重复指定行数会导致下一个分段超过 {@link #maxChunkSize}，实现会从较早
     * 的重叠行开始减少，直到新数据行可以放入；单个新数据行自身过长时仍保持完整。</p>
     */
    private int overlapRows;

    /**
     * 创建不包含数据行重叠的 Markdown 表格分割器。
     *
     * @param maxChunkSize 单个表格分段的目标最大字符数，必须大于 0
     */
    public MarkdownTableSplitter(int maxChunkSize) {
        this(maxChunkSize, 0);
    }

    /**
     * 创建 Markdown 表格分割器。
     *
     * @param maxChunkSize 单个表格分段的目标最大字符数，必须大于 0
     * @param overlapRows  相邻分段期望重复的数据行数，必须大于等于 0
     */
    public MarkdownTableSplitter(int maxChunkSize, int overlapRows) {
        setMaxChunkSize(maxChunkSize);
        setOverlapRows(overlapRows);
    }

    /**
     * 获取单个表格分段的目标最大字符数。
     *
     * @return 目标最大字符数
     */
    public int getMaxChunkSize() {
        return maxChunkSize;
    }

    /**
     * 设置单个表格分段的目标最大字符数。
     *
     * @param maxChunkSize 目标最大字符数，必须大于 0
     * @throws IllegalArgumentException 当参数小于等于 0 时抛出
     */
    public void setMaxChunkSize(int maxChunkSize) {
        if (maxChunkSize <= 0) {
            throw new IllegalArgumentException("maxChunkSize must be greater than 0, maxChunkSize: " + maxChunkSize);
        }
        this.maxChunkSize = maxChunkSize;
    }

    /**
     * 获取相邻表格分段期望重复的数据行数。
     *
     * @return 重叠数据行数
     */
    public int getOverlapRows() {
        return overlapRows;
    }

    /**
     * 设置相邻表格分段期望重复的数据行数。
     *
     * @param overlapRows 重叠数据行数，必须大于等于 0
     * @throws IllegalArgumentException 当参数小于 0 时抛出
     */
    public void setOverlapRows(int overlapRows) {
        if (overlapRows < 0) {
            throw new IllegalArgumentException("overlapRows must not be negative, overlapRows: " + overlapRows);
        }
        this.overlapRows = overlapRows;
    }

    /**
     * 将文档中的长 Markdown 表格按完整数据行进行拆分。
     *
     * <p>当文档中不存在超过长度限制的表格时，返回一个内容不变的新文档；存在长表格
     * 时，表格前后的普通内容分别形成文档分段，长表格形成一个或多个表格分段。空白分段
     * 不会写入结果。</p>
     *
     * <p>所有输出文档都会复制源文档元数据。表格分段还会写入以下元数据：</p>
     * <ul>
     *     <li>{@code content_type=markdown_table}：标识内容类型；</li>
     *     <li>{@code table_index}：当前长表格在本次拆分结果中的从 0 开始索引；</li>
     *     <li>{@code table_chunk_index}：当前表格分段的从 0 开始索引；</li>
     *     <li>{@code table_chunk_count}：当前表格总分段数。</li>
     * </ul>
     *
     * @param document    待拆分文档；为 {@code null} 或内容为空时返回空列表
     * @param idGenerator 可选的文档 ID 生成器；表格附加元数据会在生成 ID 前写入
     * @return 按原文顺序排列的新文档列表
     */
    @Override
    public List<Document> split(Document document, DocumentIdGenerator idGenerator) {
        if (document == null || StringUtil.noText(document.getContent())) {
            return Collections.emptyList();
        }

        String[] lines = document.getContent().split("\\r?\\n", -1);
        List<TableBlock> longTables = findLongTables(lines);
        if (longTables.isEmpty()) {
            // 即使无需拆表也返回新 Document，与其他 DocumentSplitter 的行为保持一致。
            return Collections.singletonList(createDocument(document, document.getContent(), idGenerator));
        }

        // 依次输出表格前的正文和表格分段，最后补上末尾正文，以维持原文顺序。
        List<Document> result = new ArrayList<>();
        int contentStart = 0;
        for (int tableIndex = 0; tableIndex < longTables.size(); tableIndex++) {
            TableBlock table = longTables.get(tableIndex);
            addPlainContent(result, lines, contentStart, table.startLine, document, idGenerator);
            addTableChunks(result, lines, table, tableIndex, document, idGenerator);
            contentStart = table.endLine + 1;
        }
        addPlainContent(result, lines, contentStart, lines.length, document, idGenerator);
        return result;
    }

    /**
     * 扫描所有行，定位长度超过限制的 Markdown 表格。
     *
     * <p>表格必须由列数相同的表头行和分隔行开始。扫描期间会记录围栏字符及其长度，
     * 只有相同字符且长度不短于开启围栏的结束标记才能关闭代码块，避免四反引号代码块中
     * 的三反引号被错误识别为结束标记。</p>
     *
     * @param lines 已按换行符拆开的文档内容
     * @return 需要拆分的长表格位置列表
     */
    private List<TableBlock> findLongTables(String[] lines) {
        List<TableBlock> result = new ArrayList<>();
        char fenceMarker = 0;
        int fenceLength = 0;

        for (int i = 0; i < lines.length; i++) {
            String trimmed = stripLeading(lines[i]);
            int currentFenceLength = fenceRunLength(trimmed);
            if (fenceMarker == 0 && currentFenceLength >= 3) {
                // 记录开启围栏的字符类型和长度，后续所有代码块内容均跳过表格检测。
                fenceMarker = trimmed.charAt(0);
                fenceLength = currentFenceLength;
                continue;
            }
            if (fenceMarker != 0) {
                if (currentFenceLength >= fenceLength && trimmed.charAt(0) == fenceMarker
                    && trimmed.substring(currentFenceLength).trim().isEmpty()) {
                    fenceMarker = 0;
                    fenceLength = 0;
                }
                continue;
            }
            if (i + 1 >= lines.length || !isTableStart(lines[i], lines[i + 1])) {
                continue;
            }

            int endLine = i + 1;
            // Markdown 表格遇到空行或不含未转义竖线的行即结束。
            while (endLine + 1 < lines.length && isTableRow(lines[endLine + 1])) {
                endLine++;
            }
            if (joinedLength(lines, i, endLine + 1) > maxChunkSize) {
                result.add(new TableBlock(i, endLine));
            }
            i = endLine;
        }
        return result;
    }

    /**
     * 将指定的非表格行区间合并为普通文档分段。
     *
     * @param result      输出列表
     * @param lines       全部文档行
     * @param start       起始行索引，包含
     * @param end         结束行索引，不包含
     * @param source      源文档
     * @param idGenerator 可选的 ID 生成器
     */
    private void addPlainContent(List<Document> result, String[] lines, int start, int end,
                                 Document source, DocumentIdGenerator idGenerator) {
        String content = joinLines(lines, start, end).trim();
        if (!content.isEmpty()) {
            result.add(createDocument(source, content, idGenerator));
        }
    }

    /**
     * 将一个长表格拆成若干合法的 Markdown 表格分段并加入输出列表。
     *
     * <p>表头行和分隔行组成每个分段都必须包含的固定前缀。遍历数据行时，如果加入
     * 下一行会超过限制，则先输出当前分段，再根据 {@link #overlapRows} 构造下一分段
     * 的重叠部分。所有表格元数据写入完成后才调用 ID 生成器。</p>
     *
     * @param result      输出列表
     * @param lines       全部文档行
     * @param table       当前长表格的行区间
     * @param tableIndex  当前长表格的索引
     * @param source      源文档
     * @param idGenerator 可选的 ID 生成器
     */
    private void addTableChunks(List<Document> result, String[] lines, TableBlock table, int tableIndex,
                                Document source, DocumentIdGenerator idGenerator) {
        String prefix = lines[table.startLine] + "\n" + lines[table.startLine + 1];
        List<String> rows = new ArrayList<>();
        List<String> chunks = new ArrayList<>();

        for (int lineIndex = table.startLine + 2; lineIndex <= table.endLine; lineIndex++) {
            String row = lines[lineIndex];
            if (!rows.isEmpty() && tableLength(prefix, rows, row) > maxChunkSize) {
                // 当前分段已有数据行时才结算，避免为单个超长行产生空表格分段。
                chunks.add(buildTableChunk(prefix, rows));
                rows = overlappingRows(rows, prefix, row);
            }
            rows.add(row);
        }

        if (!rows.isEmpty()) {
            chunks.add(buildTableChunk(prefix, rows));
        } else {
            chunks.add(prefix);
        }

        for (int chunkIndex = 0; chunkIndex < chunks.size(); chunkIndex++) {
            Document chunk = createDocument(source, chunks.get(chunkIndex), null);
            chunk.putMetadata("content_type", "markdown_table");
            chunk.putMetadata("table_index", String.valueOf(tableIndex));
            chunk.putMetadata("table_chunk_index", String.valueOf(chunkIndex));
            chunk.putMetadata("table_chunk_count", String.valueOf(chunks.size()));
            chunk.setId(idGenerator == null ? null : idGenerator.generateId(chunk));
            result.add(chunk);
        }
    }

    /**
     * 从上一分段尾部选取可放入下一分段的重叠行。
     *
     * <p>优先保留距离下一行最近的数据行。如果“表头 + 重叠行 + 下一行”超过限制，
     * 则从最早的重叠行开始移除。该方法返回可修改的新列表，不会修改上一分段的数据。</p>
     *
     * @param previousRows 上一分段的数据行
     * @param prefix       表头与分隔行组成的固定前缀
     * @param nextRow      下一分段必须加入的新数据行
     * @return 实际可保留的重叠数据行
     */
    private List<String> overlappingRows(List<String> previousRows, String prefix, String nextRow) {
        int start = Math.max(0, previousRows.size() - overlapRows);
        List<String> overlap = new ArrayList<>(previousRows.subList(start, previousRows.size()));
        while (!overlap.isEmpty() && tableLength(prefix, overlap, nextRow) > maxChunkSize) {
            overlap.remove(0);
        }
        return overlap;
    }

    /**
     * 计算加入下一行后的完整表格字符数，换行符按一个字符计入。
     */
    private static int tableLength(String prefix, List<String> rows, String nextRow) {
        int length = prefix.length();
        for (String row : rows) {
            length += 1 + row.length();
        }
        return length + 1 + nextRow.length();
    }

    /**
     * 使用固定表头前缀和数据行构建一个完整 Markdown 表格分段。
     */
    private static String buildTableChunk(String prefix, List<String> rows) {
        StringBuilder content = new StringBuilder(prefix);
        for (String row : rows) {
            content.append('\n').append(row);
        }
        return content.toString();
    }

    /**
     * 判断相邻两行是否构成 Markdown 表格的起始部分。
     *
     * <p>表头必须包含至少一个未转义的竖线；分隔行的列数必须与表头一致，且每个分隔
     * 单元格只能包含连字符以及可选的首尾冒号，例如 {@code ---}、{@code :---}、
     * {@code ---:} 或 {@code :---:}。</p>
     */
    private static boolean isTableStart(String header, String delimiter) {
        List<String> headerCells = splitCells(header);
        List<String> delimiterCells = splitCells(delimiter);
        if (!hasUnescapedPipe(header) || headerCells.isEmpty() || headerCells.size() != delimiterCells.size()) {
            return false;
        }
        for (String cell : delimiterCells) {
            if (!cell.trim().matches(":?-+:?")) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断一行是否可继续作为当前表格的数据行。
     */
    private static boolean isTableRow(String line) {
        return !line.trim().isEmpty() && hasUnescapedPipe(line);
    }

    /**
     * 按未转义的竖线拆分单元格，并移除首尾竖线产生的空占位单元格。
     *
     * <p>转义竖线（{@code \|}）属于单元格内容，不作为列边界。</p>
     */
    private static List<String> splitCells(String line) {
        String value = line == null ? "" : line.trim();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean escaped = false;

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '|' && !escaped) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
            if (c == '\\' && !escaped) {
                escaped = true;
            } else {
                escaped = false;
            }
        }
        cells.add(cell.toString());

        if (value.startsWith("|") && !cells.isEmpty()) {
            cells.remove(0);
        }
        if (endsWithUnescapedPipe(value) && !cells.isEmpty()) {
            cells.remove(cells.size() - 1);
        }
        return cells;
    }

    /**
     * 检查一行是否包含未被反斜杠转义的竖线。
     */
    private static boolean hasUnescapedPipe(String line) {
        if (line == null) {
            return false;
        }
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '|' && !escaped) {
                return true;
            }
            if (c == '\\' && !escaped) {
                escaped = true;
            } else {
                escaped = false;
            }
        }
        return false;
    }

    /**
     * 判断字符串末尾的竖线是否未转义；连续反斜杠数量为偶数时竖线未转义。
     */
    private static boolean endsWithUnescapedPipe(String value) {
        if (!value.endsWith("|")) {
            return false;
        }
        int slashCount = 0;
        for (int i = value.length() - 2; i >= 0 && value.charAt(i) == '\\'; i--) {
            slashCount++;
        }
        return slashCount % 2 == 0;
    }

    /**
     * 统计行首连续的反引号或波浪号数量，非围栏候选行返回 0。
     */
    private static int fenceRunLength(String line) {
        if (line.isEmpty() || (line.charAt(0) != '`' && line.charAt(0) != '~')) {
            return 0;
        }
        char marker = line.charAt(0);
        int length = 1;
        while (length < line.length() && line.charAt(length) == marker) {
            length++;
        }
        return length;
    }

    /**
     * 计算左闭右开行区间拼接后的字符数，包括行之间的换行符。
     */
    private static int joinedLength(String[] lines, int start, int end) {
        int length = Math.max(0, end - start - 1);
        for (int i = start; i < end; i++) {
            length += lines[i].length();
        }
        return length;
    }

    /**
     * 使用换行符连接左闭右开行区间。
     */
    private static String joinLines(String[] lines, int start, int end) {
        StringBuilder result = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (i > start) {
                result.append('\n');
            }
            result.append(lines[i]);
        }
        return result.toString();
    }

    /**
     * 移除行首空白，用于识别允许缩进的围栏标记。
     */
    private static String stripLeading(String value) {
        int index = 0;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return value.substring(index);
    }

    /**
     * 创建输出文档，复制源元数据，并在设置内容后生成 ID。
     */
    private static Document createDocument(Document source, String content, DocumentIdGenerator idGenerator) {
        Document result = new Document();
        result.putMetadata(source.getMetadataMap());
        result.setContent(content);
        result.setTitle(source.getTitle());
        result.setId(idGenerator == null ? null : idGenerator.generateId(result));
        return result;
    }

    /**
     * Markdown 长表格在原始行数组中的闭区间位置。
     */
    private static class TableBlock {
        /** 表头所在的起始行索引。 */
        final int startLine;

        /** 最后一条数据行所在的结束行索引。 */
        final int endLine;

        TableBlock(int startLine, int endLine) {
            this.startLine = startLine;
            this.endLine = endLine;
        }
    }
}

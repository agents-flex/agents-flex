package com.agentsflex.doc.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 表格中的一个锚点单元格。
 *
 * <p>横向或纵向合并区域仅在左上角创建本对象，被合并覆盖的其他逻辑位置不创建对象。
 * {@link #columnIndex}、{@link #rowSpan} 和 {@link #colSpan} 共同描述该单元格在完整网格中的位置。</p>
 */
public final class TableCell {
    /**
     * 单元格内按源文档顺序保存的块级内容，可包含段落、图片、列表和嵌套表格。
     */
    private final List<DocumentBlock> content;

    /**
     * 单元格锚点所在的零基逻辑列索引。
     */
    private final int columnIndex;

    /**
     * 单元格纵向覆盖的行数，最小值为 1。
     */
    private int rowSpan;

    /**
     * 单元格横向覆盖的列数，最小值为 1。
     */
    private final int colSpan;

    /**
     * 是否应使用 HTML {@code th} 语义渲染。
     */
    private final boolean header;

    /**
     * 创建一个结构化表格单元格。
     *
     * <p>负数列索引会规范为 0，非正跨度会规范为 1，从而保证生成的 HTML 属性合法。</p>
     *
     * @param text        单元格文本，可为 {@code null}
     * @param columnIndex 零基逻辑列索引
     * @param rowSpan     纵向覆盖行数
     * @param colSpan     横向覆盖列数
     * @param header      是否为表头单元格
     */
    public TableCell(String text, int columnIndex, int rowSpan, int colSpan, boolean header) {
        this(text == null || text.isEmpty()
                ? Collections.<DocumentBlock>emptyList()
                : Collections.<DocumentBlock>singletonList(new ParagraphBlock(text)),
            columnIndex, rowSpan, colSpan, header);
    }

    /**
     * 使用结构化块创建表格单元格。
     *
     * @param content     单元格块级内容，可包含多个段落、图片或嵌套表格
     * @param columnIndex 零基逻辑列索引
     * @param rowSpan     纵向覆盖行数
     * @param colSpan     横向覆盖列数
     * @param header      是否为表头单元格
     */
    public TableCell(List<DocumentBlock> content, int columnIndex, int rowSpan,
                     int colSpan, boolean header) {
        this.content = content == null ? Collections.<DocumentBlock>emptyList()
            : Collections.unmodifiableList(new ArrayList<>(content));
        this.columnIndex = Math.max(0, columnIndex);
        this.rowSpan = Math.max(1, rowSpan);
        this.colSpan = Math.max(1, colSpan);
        this.header = header;
    }

    /**
     * 获取单元格内容的兼容纯文本表示。
     *
     * <p>该方法供旧调用方和无法表达复杂结构的 Markdown 表格降级使用；需要保留完整结构时
     * 应调用 {@link #getContent()}。</p>
     *
     * @return 非 {@code null} 的兼容纯文本
     */
    public String getText() {
        StringBuilder text = new StringBuilder();
        for (DocumentBlock block : content) {
            String value = plainText(block);
            if (value.isEmpty()) continue;
            if (text.length() > 0) text.append('\n');
            text.append(value);
        }
        return text.toString();
    }

    /**
     * 获取单元格内的结构化块。
     *
     * @return 不可修改的块列表
     */
    public List<DocumentBlock> getContent() {
        return content;
    }

    /**
     * 将一个块降级为纯文本，供旧接口和 Markdown 表格使用。
     */
    private String plainText(DocumentBlock block) {
        if (block instanceof ParagraphBlock) return ((ParagraphBlock) block).getPlainText();
        if (block instanceof HeadingBlock) return ((HeadingBlock) block).getPlainText();
        if (block instanceof ImageBlock) return ((ImageBlock) block).getAltText();
        if (block instanceof CodeBlock) return ((CodeBlock) block).getCode();
        if (block instanceof TextBlock) return ((TextBlock) block).getText();
        if (block instanceof SectionBlock) return ((SectionBlock) block).getTitle();
        return "";
    }

    /**
     * 获取单元格锚点的零基逻辑列索引。
     *
     * @return 逻辑列索引
     */
    public int getColumnIndex() {
        return columnIndex;
    }

    /**
     * 获取单元格纵向覆盖的行数。
     *
     * @return 大于等于 1 的行跨度
     */
    public int getRowSpan() {
        return rowSpan;
    }

    /**
     * 获取单元格横向覆盖的列数。
     *
     * @return 大于等于 1 的列跨度
     */
    public int getColSpan() {
        return colSpan;
    }

    /**
     * 判断单元格是否具有表头语义。
     *
     * @return 应渲染为 {@code th} 时返回 {@code true}
     */
    public boolean isHeader() {
        return header;
    }

    /**
     * 将纵向跨度增加一行。
     *
     * <p>DOCX 等格式使用“起始单元格 + 后续继续标记”表示纵向合并，解析器每发现一个
     * 继续标记便调用本方法扩展锚点单元格的行跨度。</p>
     */
    public void incrementRowSpan() {
        rowSpan++;
    }
}

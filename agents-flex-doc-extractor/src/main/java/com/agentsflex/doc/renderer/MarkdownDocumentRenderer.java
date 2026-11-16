package com.agentsflex.doc.renderer;

import com.agentsflex.doc.extractor.MarkdownFormatter;
import com.agentsflex.doc.model.DocumentBlock;
import com.agentsflex.doc.model.ExtractedDocument;
import com.agentsflex.doc.model.TableBlock;
import com.agentsflex.doc.model.TableCell;
import com.agentsflex.doc.model.TableRow;
import com.agentsflex.doc.model.TextBlock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 纯 Markdown 文档渲染器。
 *
 * <p>该实现维持原有 Doc Extractor 的默认输出行为。标准 Markdown 无法表达单元格合并，
 * 因此表格会被展开为规则二维网格：锚点单元格保留文本，被合并覆盖的位置输出空单元格。</p>
 */
public final class MarkdownDocumentRenderer implements DocumentRenderer {

    /**
     * 将结构化文档渲染为纯 Markdown。
     *
     * @param document 待渲染的结构化文档
     * @return Markdown 文本；文档为空时返回空字符串
     */
    @Override
    public String render(ExtractedDocument document) {
        if (document == null || document.isEmpty()) return "";
        StringBuilder output = new StringBuilder();
        for (DocumentBlock block : document.getBlocks()) {
            if (block instanceof TableBlock) {
                MarkdownFormatter.appendTable(output, expandTable((TableBlock) block));
            } else if (block instanceof TextBlock) {
                appendText(output, (TextBlock) block);
            }
        }
        return output.toString().trim();
    }

    /**
     * 将单个文本块追加为 Markdown。
     *
     * @param output 目标输出缓冲区
     * @param block  待渲染文本块
     */
    private void appendText(StringBuilder output, TextBlock block) {
        String text = block.getText();
        if (text == null || text.trim().isEmpty()) return;
        RendererSupport.ensureBlankLine(output);
        switch (block.getType()) {
            case HEADING:
                output.append(repeat("#", block.getLevel())).append(' ').append(text).append("\n\n");
                break;
            case LIST_ITEM:
                output.append("- ").append(text).append('\n');
                break;
            case RAW_MARKDOWN:
            case PARAGRAPH:
            default:
                output.append(text).append("\n\n");
                break;
        }
    }

    /**
     * 将带合并信息的稀疏表格展开为 MarkdownFormatter 可处理的规则二维数组。
     *
     * <p>该降级过程有意不重复合并锚点的文本，避免模型或检索系统误认为同一内容出现多次。</p>
     *
     * @param table 结构化表格
     * @return 规则二维字符串列表
     */
    private List<List<String>> expandTable(TableBlock table) {
        int rowCount = table.getRows().size();
        int columnCount = 0;
        for (TableRow row : table.getRows()) {
            for (TableCell cell : row.getCells()) {
                columnCount = Math.max(columnCount, cell.getColumnIndex() + cell.getColSpan());
            }
        }
        if (rowCount == 0 || columnCount == 0) return Collections.emptyList();
        List<List<String>> grid = new ArrayList<>();
        for (int row = 0; row < rowCount; row++) {
            grid.add(new ArrayList<>(Collections.nCopies(columnCount, "")));
        }
        for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
            for (TableCell cell : table.getRows().get(rowIndex).getCells()) {
                if (cell.getColumnIndex() < columnCount) {
                    grid.get(rowIndex).set(cell.getColumnIndex(), cell.getText());
                }
            }
        }
        return grid;
    }

    /**
     * 重复指定字符串，用于生成 Markdown 标题前缀。
     *
     * @param value 要重复的字符串
     * @param times 重复次数
     * @return 重复后的字符串
     */
    private String repeat(String value, int times) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < times; i++) result.append(value);
        return result.toString();
    }
}

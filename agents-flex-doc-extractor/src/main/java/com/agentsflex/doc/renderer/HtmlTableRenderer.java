package com.agentsflex.doc.renderer;

import com.agentsflex.doc.model.TableBlock;
import com.agentsflex.doc.model.TableCell;
import com.agentsflex.doc.model.TableRow;

/**
 * 保留单元格合并和单元格内部块结构的 HTML 表格渲染器。
 */
public final class HtmlTableRenderer {
    /**
     * 用于递归渲染单元格内部块的 HTML 渲染器。
     */
    private final HtmlDocumentRenderer documentRenderer;

    /**
     * 创建使用默认 HTML 文档渲染器的表格渲染器。
     */
    public HtmlTableRenderer() {
        this(new HtmlDocumentRenderer());
    }

    /**
     * 创建指定递归渲染器的表格渲染器。
     */
    HtmlTableRenderer(HtmlDocumentRenderer documentRenderer) {
        this.documentRenderer = documentRenderer;
    }

    /**
     * 将结构化表格追加到输出缓冲区。
     *
     * @param table  表格
     * @param output 输出缓冲区
     */
    public void render(TableBlock table, StringBuilder output) {
        output.append("<table>\n<tbody>\n");
        for (TableRow row : table.getRows()) {
            output.append("<tr>");
            for (TableCell cell : row.getCells()) {
                String tag = cell.isHeader() ? "th" : "td";
                output.append('<').append(tag);
                if (cell.getRowSpan() > 1) output.append(" rowspan=\"").append(cell.getRowSpan()).append('"');
                if (cell.getColSpan() > 1) output.append(" colspan=\"").append(cell.getColSpan()).append('"');
                output.append('>');
                documentRenderer.renderBlocks(cell.getContent(), output);
                trimFinalNewline(output);
                output.append("</").append(tag).append('>');
            }
            output.append("</tr>\n");
        }
        output.append("</tbody>\n</table>");
    }

    /**
     * 删除递归渲染产生的单元格末尾换行。
     */
    private void trimFinalNewline(StringBuilder output) {
        if (output.length() > 0 && output.charAt(output.length() - 1) == '\n') output.deleteCharAt(output.length() - 1);
    }
}

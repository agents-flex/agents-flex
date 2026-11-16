package com.agentsflex.doc.renderer;

import com.agentsflex.doc.model.TableBlock;
import com.agentsflex.doc.model.TableCell;
import com.agentsflex.doc.model.TableRow;

/**
 * HTML 表格渲染器。
 *
 * <p>渲染结果使用 {@code table/tbody/tr/th/td} 标签，并仅在跨度大于 1 时输出
 * {@code rowspan} 或 {@code colspan}。单元格文本会进行 HTML 转义，换行转换为 {@code br}。</p>
 */
public final class HtmlTableRenderer {

    /**
     * 将结构化表格追加到指定输出缓冲区。
     *
     * @param table  待渲染表格，不应为 {@code null}
     * @param output 目标输出缓冲区，不应为 {@code null}
     */
    public void render(TableBlock table, StringBuilder output) {
        output.append("<table>\n<tbody>\n");
        for (TableRow row : table.getRows()) {
            output.append("<tr>");
            for (TableCell cell : row.getCells()) {
                String tag = cell.isHeader() ? "th" : "td";
                output.append('<').append(tag);
                if (cell.getRowSpan() > 1) {
                    output.append(" rowspan=\"").append(cell.getRowSpan()).append('"');
                }
                if (cell.getColSpan() > 1) {
                    output.append(" colspan=\"").append(cell.getColSpan()).append('"');
                }
                output.append('>').append(RendererSupport.renderCellHtml(cell.getText()))
                    .append("</").append(tag).append('>');
            }
            output.append("</tr>\n");
        }
        output.append("</tbody>\n</table>");
    }
}

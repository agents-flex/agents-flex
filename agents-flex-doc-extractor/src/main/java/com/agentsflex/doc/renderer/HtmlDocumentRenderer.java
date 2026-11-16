package com.agentsflex.doc.renderer;

import com.agentsflex.doc.model.DocumentBlock;
import com.agentsflex.doc.model.ExtractedDocument;
import com.agentsflex.doc.model.TableBlock;
import com.agentsflex.doc.model.TextBlock;

/**
 * 完整 HTML 文档片段渲染器。
 *
 * <p>输出内容不包含 {@code html}、{@code head} 和 {@code body} 外壳，便于调用方嵌入现有页面。
 * 所有文本均经过 HTML 转义；表格由 {@link HtmlTableRenderer} 保留合并单元格。</p>
 */
public final class HtmlDocumentRenderer implements DocumentRenderer {
    /**
     * 专门负责 HTML 表格序列化的渲染器。
     */
    private final HtmlTableRenderer tableRenderer = new HtmlTableRenderer();

    /**
     * 将结构化文档渲染为完整 HTML 片段。
     *
     * @param document 待渲染的结构化文档
     * @return HTML 片段；文档为空时返回空字符串
     */
    @Override
    public String render(ExtractedDocument document) {
        if (document == null || document.isEmpty()) return "";
        StringBuilder output = new StringBuilder();
        for (DocumentBlock block : document.getBlocks()) {
            if (block instanceof TableBlock) tableRenderer.render((TableBlock) block, output);
            else if (block instanceof TextBlock) renderText((TextBlock) block, output);
            output.append('\n');
        }
        return output.toString().trim();
    }

    /**
     * 将一个文本块渲染为对应的 HTML 元素。
     *
     * <p>原始 Markdown 块不会被解释为 HTML，而是作为普通段落转义输出，避免不受信任内容
     * 注入标签或脚本。</p>
     *
     * @param block  文本块
     * @param output 目标输出缓冲区
     */
    private void renderText(TextBlock block, StringBuilder output) {
        String content = RendererSupport.renderCellHtml(block.getText());
        switch (block.getType()) {
            case HEADING:
                output.append("<h").append(block.getLevel()).append('>')
                    .append(content).append("</h").append(block.getLevel()).append('>');
                break;
            case LIST_ITEM:
                output.append("<ul><li>").append(content).append("</li></ul>");
                break;
            case RAW_MARKDOWN:
            case PARAGRAPH:
            default:
                output.append("<p>").append(content).append("</p>");
                break;
        }
    }
}

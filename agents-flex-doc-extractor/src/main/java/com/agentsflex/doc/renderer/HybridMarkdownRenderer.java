package com.agentsflex.doc.renderer;

import com.agentsflex.doc.model.DocumentBlock;
import com.agentsflex.doc.model.ExtractedDocument;
import com.agentsflex.doc.model.TableBlock;

/**
 * Markdown 与 HTML 表格融合渲染器。
 *
 * <p>标题、段落和列表等普通块使用 Markdown；所有表格使用 HTML，以保留合并单元格。
 * HTML 表格前后会保留空行，使其在 CommonMark 等解析器中形成独立 HTML 块。</p>
 */
public final class HybridMarkdownRenderer implements DocumentRenderer {
    /**
     * 普通非表格块使用的 Markdown 渲染器。
     */
    private final MarkdownDocumentRenderer markdownRenderer = new MarkdownDocumentRenderer();

    /**
     * 表格块使用的 HTML 渲染器。
     */
    private final HtmlTableRenderer tableRenderer = new HtmlTableRenderer();

    /**
     * 将结构化文档渲染为 Markdown 正文与 HTML 表格的融合文本。
     *
     * @param document 待渲染的结构化文档
     * @return 融合格式文本；文档为空时返回空字符串
     */
    @Override
    public String render(ExtractedDocument document) {
        if (document == null || document.isEmpty()) return "";
        StringBuilder output = new StringBuilder();
        for (DocumentBlock block : document.getBlocks()) {
            RendererSupport.ensureBlankLine(output);
            if (block instanceof TableBlock) {
                tableRenderer.render((TableBlock) block, output);
                output.append("\n\n");
            } else {
                String rendered = markdownRenderer.render(new ExtractedDocument().addBlock(block));
                if (!rendered.isEmpty()) output.append(rendered).append("\n\n");
            }
        }
        return output.toString().trim();
    }
}

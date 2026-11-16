package com.agentsflex.doc.renderer;

import com.agentsflex.doc.model.*;

import java.util.List;

/**
 * 将结构化文档渲染为安全的 HTML 文档片段。
 *
 * <p>输出不包含 {@code html/head/body} 外壳。文本和属性会转义，链接与图片地址会进行
 * 协议白名单校验，源文档中的脚本或事件属性不会被透传。</p>
 */
public final class HtmlDocumentRenderer implements DocumentRenderer {

    /** {@inheritDoc} */
    @Override
    public String render(ExtractedDocument document) {
        if (document == null || document.isEmpty()) return "";
        StringBuilder output = new StringBuilder();
        renderBlocks(document.getBlocks(), output);
        return output.toString().trim();
    }

    /**
     * 将多个块追加为 HTML，供表格单元格和章节递归调用。
     *
     * @param blocks 块列表
     * @param output 输出缓冲区
     */
    void renderBlocks(List<DocumentBlock> blocks, StringBuilder output) {
        if (blocks == null) return;
        for (DocumentBlock block : blocks) {
            renderBlock(block, output);
            output.append('\n');
        }
    }

    /** 将单个结构化块追加为 HTML。 */
    private void renderBlock(DocumentBlock block, StringBuilder output) {
        if (block instanceof ParagraphBlock) {
            output.append("<p>").append(RendererSupport.renderHtmlInlines(
                ((ParagraphBlock) block).getContent())).append("</p>");
        } else if (block instanceof HeadingBlock) {
            HeadingBlock heading = (HeadingBlock) block;
            output.append("<h").append(heading.getLevel()).append('>')
                .append(RendererSupport.renderHtmlInlines(heading.getContent()))
                .append("</h").append(heading.getLevel()).append('>');
        } else if (block instanceof ImageBlock) {
            ImageBlock image = (ImageBlock) block;
            RendererSupport.appendHtmlImage(output, image.getSource(), image.getAltText(), image.getTitle());
        } else if (block instanceof ListBlock) renderList((ListBlock) block, output);
        else if (block instanceof QuoteBlock) {
            output.append("<blockquote>\n");
            renderBlocks(((QuoteBlock) block).getContent(), output);
            output.append("</blockquote>");
        } else if (block instanceof CodeBlock) {
            CodeBlock code = (CodeBlock) block;
            output.append("<pre><code");
            if (code.getLanguage() != null && !code.getLanguage().isEmpty()) {
                output.append(" class=\"language-").append(RendererSupport.escapeHtml(code.getLanguage())).append('"');
            }
            output.append('>').append(RendererSupport.escapeHtml(code.getCode())).append("</code></pre>");
        } else if (block instanceof HorizontalRuleBlock) output.append("<hr>");
        else if (block instanceof PageBreakBlock) output.append("<div class=\"page-break\"></div>");
        else if (block instanceof SectionBlock) renderSection((SectionBlock) block, output);
        else if (block instanceof TableBlock) new HtmlTableRenderer(this).render((TableBlock) block, output);
        else if (block instanceof TextBlock) renderLegacyText((TextBlock) block, output);
    }

    /** 渲染列表和嵌套列表。 */
    private void renderList(ListBlock list, StringBuilder output) {
        String tag = list.isOrdered() ? "ol" : "ul";
        output.append('<').append(tag);
        if (list.isOrdered() && list.getStart() != 1) output.append(" start=\"").append(list.getStart()).append('"');
        output.append('>');
        for (ListItem item : list.getItems()) {
            output.append("<li>");
            renderBlocks(item.getContent(), output);
            if (item.getChildren() != null) renderList(item.getChildren(), output);
            output.append("</li>");
        }
        output.append("</").append(tag).append('>');
    }

    /** 渲染章节容器及其标题。 */
    private void renderSection(SectionBlock section, StringBuilder output) {
        output.append("<section data-section-type=\"")
            .append(section.getType().name().toLowerCase()).append("\">");
        if (!section.getTitle().isEmpty()) {
            output.append("<h3>").append(RendererSupport.escapeHtml(section.getTitle())).append("</h3>");
        }
        output.append('\n');
        renderBlocks(section.getContent(), output);
        output.append("</section>");
    }

    /** 兼容尚未升级的解析器产生的旧 TextBlock。 */
    private void renderLegacyText(TextBlock block, StringBuilder output) {
        String content = RendererSupport.renderCellHtml(block.getText());
        if (block.getType() == TextBlock.Type.HEADING) {
            output.append("<h").append(block.getLevel()).append('>').append(content)
                .append("</h").append(block.getLevel()).append('>');
        } else if (block.getType() == TextBlock.Type.LIST_ITEM) {
            output.append("<ul><li>").append(content).append("</li></ul>");
        } else output.append("<p>").append(content).append("</p>");
    }
}

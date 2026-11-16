package com.agentsflex.doc.renderer;

import com.agentsflex.doc.model.*;

import java.util.List;

/**
 * Markdown 正文与 HTML 表格融合渲染器。
 *
 * <p>普通块仍使用 Markdown；无论表格出现在顶层、章节、引用、列表项还是单元格内部，
 * 都会使用 HTML 渲染，从而完整保留行列合并和嵌套结构。</p>
 */
public final class HybridMarkdownRenderer implements DocumentRenderer {
    /**
     * 普通块的 Markdown 渲染器。
     */
    private final MarkdownDocumentRenderer markdownRenderer = new MarkdownDocumentRenderer();
    /**
     * 表格的 HTML 渲染器。
     */
    private final HtmlTableRenderer tableRenderer = new HtmlTableRenderer();

    /**
     * {@inheritDoc}
     */
    @Override
    public String render(ExtractedDocument document) {
        if (document == null || document.isEmpty()) return "";
        StringBuilder output = new StringBuilder();
        renderBlocks(document.getBlocks(), output);
        return output.toString().trim();
    }

    /**
     * 递归渲染一组块。
     */
    private void renderBlocks(List<DocumentBlock> blocks, StringBuilder output) {
        for (DocumentBlock block : blocks) {
            if (block instanceof TableBlock) {
                RendererSupport.ensureBlankLine(output);
                tableRenderer.render((TableBlock) block, output);
                output.append("\n\n");
            } else if (block instanceof SectionBlock) {
                SectionBlock section = (SectionBlock) block;
                if (!section.getTitle().isEmpty()) {
                    int level = section.getType() == SectionType.SHEET ? 3 : 2;
                    appendMarkdown(output, new HeadingBlock(section.getTitle(), level));
                }
                renderBlocks(section.getContent(), output);
            } else if (block instanceof ListBlock && containsTable(block)) {
                renderListWithTables((ListBlock) block, output, 0);
            } else if (block instanceof QuoteBlock && containsTable(block)) {
                renderQuoteWithTables((QuoteBlock) block, output);
            } else {
                appendMarkdown(output, block);
            }
        }
    }

    /**
     * 使用 Markdown 渲染一个非表格块。
     */
    private void appendMarkdown(StringBuilder output, DocumentBlock block) {
        String rendered = markdownRenderer.render(new ExtractedDocument().addBlock(block));
        if (rendered.isEmpty()) return;
        RendererSupport.ensureBlankLine(output);
        output.append(rendered).append("\n\n");
    }

    /**
     * 渲染包含表格的列表，普通内容保留 Markdown，表格仍输出 HTML。
     */
    private void renderListWithTables(ListBlock list, StringBuilder output, int depth) {
        int number = list.getStart();
        for (ListItem item : list.getItems()) {
            String marker = list.isOrdered() ? (number++) + ". " : "- ";
            boolean markerWritten = false;
            for (DocumentBlock block : item.getContent()) {
                if (block instanceof TableBlock) {
                    if (!markerWritten) {
                        RendererSupport.ensureBlankLine(output);
                        output.append(repeat("  ", depth)).append(marker).append('\n');
                        markerWritten = true;
                    }
                    RendererSupport.ensureBlankLine(output);
                    tableRenderer.render((TableBlock) block, output);
                    output.append("\n\n");
                } else {
                    String rendered = markdownRenderer.render(new ExtractedDocument().addBlock(block));
                    if (rendered.isEmpty()) continue;
                    RendererSupport.ensureBlankLine(output);
                    if (!markerWritten) {
                        output.append(repeat("  ", depth)).append(marker);
                        markerWritten = true;
                    }
                    output.append(rendered.replace("\n", "\n" + repeat("  ", depth + 1))).append('\n');
                }
            }
            if (!markerWritten) {
                RendererSupport.ensureBlankLine(output);
                output.append(repeat("  ", depth)).append(marker).append('\n');
            }
            if (item.getChildren() != null) renderListWithTables(item.getChildren(), output, depth + 1);
        }
        output.append('\n');
    }

    /**
     * 渲染包含 HTML 表格的引用，并为每一行保留 Markdown 引用前缀。
     */
    private void renderQuoteWithTables(QuoteBlock quote, StringBuilder output) {
        StringBuilder nested = new StringBuilder();
        renderBlocks(quote.getContent(), nested);
        RendererSupport.ensureBlankLine(output);
        for (String line : nested.toString().trim().split("\\r?\\n", -1)) {
            output.append("> ").append(line).append('\n');
        }
        output.append('\n');
    }

    /**
     * 递归判断块及其子内容是否包含表格。
     */
    private boolean containsTable(DocumentBlock block) {
        if (block instanceof TableBlock) return true;
        if (block instanceof SectionBlock) return containsTable(((SectionBlock) block).getContent());
        if (block instanceof QuoteBlock) return containsTable(((QuoteBlock) block).getContent());
        if (block instanceof ListBlock) {
            for (ListItem item : ((ListBlock) block).getItems()) {
                if (containsTable(item.getContent()) || (item.getChildren() != null && containsTable(item.getChildren()))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 判断块列表是否递归包含表格。
     */
    private boolean containsTable(List<DocumentBlock> blocks) {
        for (DocumentBlock block : blocks) if (containsTable(block)) return true;
        return false;
    }

    /**
     * 重复字符串，用于生成嵌套列表缩进。
     */
    private String repeat(String value, int times) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < times; i++) result.append(value);
        return result.toString();
    }
}

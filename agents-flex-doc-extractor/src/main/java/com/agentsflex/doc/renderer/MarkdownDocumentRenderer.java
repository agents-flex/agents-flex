package com.agentsflex.doc.renderer;

import com.agentsflex.doc.extractor.MarkdownFormatter;
import com.agentsflex.doc.model.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 纯 Markdown 文档渲染器。
 *
 * <p>Markdown 无法表达合并单元格，因此表格仍会降级为规则二维网格；单元格中的多个块
 * 会转换为紧凑纯文本。其他块和行内样式尽量使用标准 Markdown 表达。</p>
 */
public final class MarkdownDocumentRenderer implements DocumentRenderer {

    /**
     * {@inheritDoc}
     */
    @Override
    public String render(ExtractedDocument document) {
        if (document == null || document.isEmpty()) return "";
        StringBuilder output = new StringBuilder();
        renderBlocks(document.getBlocks(), output, 0);
        return output.toString().trim();
    }

    /**
     * 按顺序渲染一组块。
     */
    private void renderBlocks(List<DocumentBlock> blocks, StringBuilder output, int indent) {
        for (DocumentBlock block : blocks) renderBlock(block, output, indent);
    }

    /**
     * 将单个块追加为 Markdown。
     */
    private void renderBlock(DocumentBlock block, StringBuilder output, int indent) {
        if (block instanceof ParagraphBlock) appendParagraph(output, (ParagraphBlock) block);
        else if (block instanceof HeadingBlock) {
            HeadingBlock heading = (HeadingBlock) block;
            appendSeparated(output, repeat("#", heading.getLevel()) + " "
                + RendererSupport.renderMarkdownInlines(heading.getContent()));
        } else if (block instanceof ImageBlock) {
            ImageBlock image = (ImageBlock) block;
            List<InlineContent> inline = Collections.<InlineContent>singletonList(
                new ImageInline(image.getSource(), image.getAltText(), image.getTitle()));
            appendSeparated(output, RendererSupport.renderMarkdownInlines(inline));
        } else if (block instanceof ListBlock) renderList((ListBlock) block, output, indent);
        else if (block instanceof QuoteBlock) renderQuote((QuoteBlock) block, output);
        else if (block instanceof CodeBlock) renderCode((CodeBlock) block, output);
        else if (block instanceof HorizontalRuleBlock) appendSeparated(output, "---");
        else if (block instanceof PageBreakBlock) appendSeparated(output, "<!-- pagebreak -->");
        else if (block instanceof SectionBlock) renderSection((SectionBlock) block, output, indent);
        else if (block instanceof TableBlock) {
            RendererSupport.ensureBlankLine(output);
            MarkdownFormatter.appendTable(output, expandTable((TableBlock) block));
        } else if (block instanceof TextBlock) appendLegacyText(output, (TextBlock) block);
    }

    /**
     * 渲染普通段落。
     */
    private void appendParagraph(StringBuilder output, ParagraphBlock paragraph) {
        String text = RendererSupport.renderMarkdownInlines(paragraph.getContent());
        if (!text.trim().isEmpty()) appendSeparated(output, text);
    }

    /**
     * 渲染列表及嵌套列表。
     */
    private void renderList(ListBlock list, StringBuilder output, int indent) {
        RendererSupport.ensureBlankLine(output);
        int number = list.getStart();
        for (ListItem item : list.getItems()) {
            String prefix = list.isOrdered() ? (number++) + ". " : "- ";
            String itemText = renderCompact(item.getContent());
            output.append(repeat("  ", indent)).append(prefix).append(itemText).append('\n');
            if (item.getChildren() != null) renderList(item.getChildren(), output, indent + 1);
        }
        output.append('\n');
    }

    /**
     * 渲染引用块，对每一行添加引用前缀。
     */
    private void renderQuote(QuoteBlock quote, StringBuilder output) {
        ExtractedDocument nested = new ExtractedDocument();
        for (DocumentBlock block : quote.getContent()) nested.addBlock(block);
        String rendered = render(nested);
        StringBuilder quoted = new StringBuilder();
        for (String line : rendered.split("\\r?\\n", -1)) quoted.append("> ").append(line).append('\n');
        appendSeparated(output, quoted.toString().trim());
    }

    /**
     * 渲染围栏代码块。
     */
    private void renderCode(CodeBlock code, StringBuilder output) {
        String fence = "\u0060\u0060\u0060";
        appendSeparated(output, fence + (code.getLanguage() == null ? "" : code.getLanguage())
            + "\n" + code.getCode() + "\n" + fence);
    }

    /**
     * 渲染章节，工作表章节保持原有三级标题兼容输出。
     */
    private void renderSection(SectionBlock section, StringBuilder output, int indent) {
        if (!section.getTitle().isEmpty()) {
            int level = section.getType() == SectionType.SHEET ? 3 : 2;
            appendSeparated(output, repeat("#", level) + " " + section.getTitle());
        }
        renderBlocks(section.getContent(), output, indent);
    }

    /**
     * 兼容旧 TextBlock。
     */
    private void appendLegacyText(StringBuilder output, TextBlock block) {
        if (block.getText().trim().isEmpty()) return;
        if (block.getType() == TextBlock.Type.HEADING) appendSeparated(output,
            repeat("#", block.getLevel()) + " " + block.getText());
        else if (block.getType() == TextBlock.Type.LIST_ITEM) {
            RendererSupport.ensureBlankLine(output);
            output.append("- ").append(block.getText()).append('\n');
        } else appendSeparated(output, block.getText());
    }

    /**
     * 将块列表转换成适合列表项或 Markdown 单元格的紧凑文本。
     */
    private String renderCompact(List<DocumentBlock> blocks) {
        StringBuilder text = new StringBuilder();
        for (DocumentBlock block : blocks) {
            String value = "";
            if (block instanceof ParagraphBlock)
                value = RendererSupport.renderMarkdownInlines(((ParagraphBlock) block).getContent());
            else if (block instanceof HeadingBlock)
                value = RendererSupport.renderMarkdownInlines(((HeadingBlock) block).getContent());
            else if (block instanceof ImageBlock) {
                ImageBlock image = (ImageBlock) block;
                value = RendererSupport.renderMarkdownInlines(Collections.<InlineContent>singletonList(
                    new ImageInline(image.getSource(), image.getAltText(), image.getTitle())));
            } else if (block instanceof CodeBlock) value = ((CodeBlock) block).getCode();
            else if (block instanceof TextBlock) value = ((TextBlock) block).getText();
            if (!value.isEmpty()) {
                if (text.length() > 0) text.append(" / ");
                text.append(value.replace("\r", " ").replace("\n", " ")
                    .replaceAll("\\s+", " ").trim());
            }
        }
        return text.toString();
    }

    /**
     * 将稀疏合并表格展开为规则二维数组。
     */
    private List<List<String>> expandTable(TableBlock table) {
        int rowCount = table.getRows().size();
        int columnCount = 0;
        for (TableRow row : table.getRows())
            for (TableCell cell : row.getCells()) {
                columnCount = Math.max(columnCount, cell.getColumnIndex() + cell.getColSpan());
            }
        if (rowCount == 0 || columnCount == 0) return Collections.emptyList();
        List<List<String>> grid = new ArrayList<>();
        for (int row = 0; row < rowCount; row++) grid.add(new ArrayList<>(Collections.nCopies(columnCount, "")));
        for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
            for (TableCell cell : table.getRows().get(rowIndex).getCells()) {
                if (cell.getColumnIndex() < columnCount) {
                    String value = renderCompact(cell.getContent());
                    if (value.isEmpty()) value = cell.getText();
                    grid.get(rowIndex).set(cell.getColumnIndex(), value);
                }
            }
        }
        return grid;
    }

    /**
     * 追加一个前后留空行的 Markdown 块。
     */
    private void appendSeparated(StringBuilder output, String text) {
        if (text == null || text.trim().isEmpty()) return;
        RendererSupport.ensureBlankLine(output);
        output.append(text).append("\n\n");
    }

    /**
     * 重复字符串。
     */
    private String repeat(String value, int times) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < times; i++) result.append(value);
        return result.toString();
    }
}

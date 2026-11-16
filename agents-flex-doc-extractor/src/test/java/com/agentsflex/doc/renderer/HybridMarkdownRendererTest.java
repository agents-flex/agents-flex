package com.agentsflex.doc.renderer;

import com.agentsflex.doc.model.ExtractedDocument;
import com.agentsflex.doc.model.ListBlock;
import com.agentsflex.doc.model.ListItem;
import com.agentsflex.doc.model.ParagraphBlock;
import com.agentsflex.doc.model.QuoteBlock;
import com.agentsflex.doc.model.TableBlock;
import com.agentsflex.doc.model.TableCell;
import com.agentsflex.doc.model.TableRow;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * 验证融合渲染器在嵌套容器中仍使用 HTML 表格。
 */
public class HybridMarkdownRendererTest {

    /**
     * 验证列表项中的表格不会退化为 Markdown 表格。
     */
    @Test
    public void rendersTableInsideListAsHtml() {
        ListBlock list = new ListBlock(false).addItem(new ListItem()
            .addBlock(new ParagraphBlock("列表说明"))
            .addBlock(table()));

        String rendered = new HybridMarkdownRenderer().render(new ExtractedDocument().addBlock(list));

        assertTrue(rendered.contains("- 列表说明"));
        assertTrue(rendered.contains("<table>"));
        assertTrue(rendered.contains("rowspan=\"2\""));
    }

    /**
     * 验证引用中的表格保留 HTML，并继续处于引用语义内。
     */
    @Test
    public void rendersTableInsideQuoteAsHtml() {
        QuoteBlock quote = new QuoteBlock().addBlock(new ParagraphBlock("引用说明")).addBlock(table());

        String rendered = new HybridMarkdownRenderer().render(new ExtractedDocument().addBlock(quote));

        assertTrue(rendered.contains("> 引用说明"));
        assertTrue(rendered.contains("> <table>"));
        assertTrue(rendered.contains("> <tr><td rowspan=\"2\"><p>合并值</p></td></tr>"));
    }

    /**
     * 创建带纵向合并的测试表格。
     */
    private TableBlock table() {
        return new TableBlock()
            .addRow(new TableRow().addCell(new TableCell("合并值", 0, 2, 1, false)))
            .addRow(new TableRow());
    }
}

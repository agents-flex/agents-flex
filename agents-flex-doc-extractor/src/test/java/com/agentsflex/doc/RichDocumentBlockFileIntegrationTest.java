package com.agentsflex.doc;

import com.agentsflex.core.document.DataUriDocumentImagePublisher;
import com.agentsflex.doc.extractor.impl.DocxExtractor;
import com.agentsflex.doc.extractor.impl.ExcelExtractor;
import com.agentsflex.doc.model.*;
import com.agentsflex.doc.source.FileDocumentSource;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.Assert.*;

/**
 * 使用真实落盘文件验证丰富 Block/Inline 模型及三种渲染模式。
 *
 * <p>测试不绕过正式文件输入链路，能够同时覆盖 Office/HTML 序列化、解析器结构映射、
 * 图片发布、HTML 安全过滤以及 Markdown/HTML/融合渲染行为。</p>
 */
public class RichDocumentBlockFileIntegrationTest {

    /**
     * 验证真实 DOCX 的标题、字符样式、链接、列表、图片和表格单元格多段落。
     *
     * @throws Exception 文件创建、解析或渲染失败
     */
    @Test
    public void extractsRichDocxBlocksAndRendersImagesInHtmlCells() throws Exception {
        Path file = Files.createTempFile("agents-flex-rich-", ".docx");
        try {
            writeRichDocx(file);
            DocumentExtractionService service = new DocumentExtractionService();
            String markdown = service.extract(file.toFile(), DocumentOutputFormat.MARKDOWN);
            String html = service.extract(file.toFile(), DocumentOutputFormat.HTML);
            String hybrid = service.extract(file.toFile(), DocumentOutputFormat.MARKDOWN_WITH_HTML_TABLES);

            assertTrue(markdown.contains("# 产品说明"));
            assertTrue(markdown.contains("**加粗**"));
            assertTrue(markdown.contains("*斜体*"));
            assertTrue(markdown.contains("[官网](https://agentsflex.com)"));
            assertTrue(markdown.contains("- 第一项"));
            assertTrue(markdown.contains("![Image](data:image/png;base64,"));

            org.jsoup.nodes.Document htmlDocument = Jsoup.parseBodyFragment(html);
            assertEquals("产品说明", htmlDocument.selectFirst("h1").text());
            assertEquals("加粗", htmlDocument.selectFirst("strong").text());
            assertEquals("斜体", htmlDocument.selectFirst("em").text());
            assertEquals("https://agentsflex.com", htmlDocument.selectFirst("a").attr("href"));
            Element docxCellImage = htmlDocument.selectFirst("td img");
            assertNotNull(docxCellImage);
            assertTrue(docxCellImage.attr("src").startsWith("data:image/png;base64,"));
            assertTrue(countDirectChildren(htmlDocument.selectFirst("td"), "p") >= 2);

            org.jsoup.nodes.Document hybridDocument = Jsoup.parseBodyFragment(hybrid);
            assertNotNull(hybridDocument.selectFirst("table td img"));
            assertFalse(hybrid.contains("<td>![Image]"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * 验证真实 HTML 文件的行内语义、列表、引用、代码、危险 URL 过滤和嵌套表格。
     *
     * @throws Exception 文件写入、解析或渲染失败
     */
    @Test
    public void extractsRichHtmlAndKeepsNestedTableBlocksSafely() throws Exception {
        Path file = Files.createTempFile("agents-flex-rich-", ".html");
        try {
            String source = "<html><head><title>真实网页</title></head><body>"
                + "<h2>能力验证</h2><p>普通<strong>加粗</strong><em>斜体</em><del>删除</del>"
                + "<code>code()</code><a href='https://agentsflex.com'>安全链接</a>"
                + "<a href='javascript:alert(1)'>危险链接</a><br><img src='https://example.com/a.png' alt='示意图'></p>"
                + "<ul><li>父项<ol start='3'><li>子项</li></ol></li></ul>"
                + "<blockquote><p>引用内容</p></blockquote>"
                + "<pre><code class='language-java'>int value = 1;</code></pre><hr>"
                + "<table><tr><th rowspan='2'>名称</th><th>内容</th></tr>"
                + "<tr><td><p>第一段</p><p>第二段</p><img src='https://example.com/cell.png' alt='单元格图'>"
                + "<table><tr><td>嵌套值</td></tr></table></td></tr></table></body></html>";
            Files.write(file, source.getBytes(StandardCharsets.UTF_8));

            String html = new DocumentExtractionService().extract(file.toFile(), DocumentOutputFormat.HTML);
            String hybrid = new DocumentExtractionService().extract(
                file.toFile(), DocumentOutputFormat.MARKDOWN_WITH_HTML_TABLES);
            org.jsoup.nodes.Document parsed = Jsoup.parseBodyFragment(html);

            assertEquals("加粗", parsed.selectFirst("strong").text());
            assertEquals("斜体", parsed.selectFirst("em").text());
            assertEquals("删除", parsed.selectFirst("del").text());
            assertEquals("code()", parsed.selectFirst("p code").text());
            assertEquals(1, parsed.select("a[href]").size());
            assertTrue(parsed.body().text().contains("危险链接"));
            assertEquals("3", parsed.selectFirst("ol").attr("start"));
            assertEquals("引用内容", parsed.selectFirst("blockquote").text());
            assertEquals("language-java", parsed.selectFirst("pre code").className());
            assertNotNull(parsed.selectFirst("hr"));
            assertEquals("2", parsed.selectFirst("th").attr("rowspan"));
            Element contentCell = parsed.selectFirst("body > table > tbody > tr:nth-child(2) > td");
            assertTrue(countDirectChildren(contentCell, "p") >= 2);
            assertEquals("第一段", contentCell.children().select("p").get(0).text());
            assertEquals("第二段", contentCell.children().select("p").get(1).text());
            assertEquals("嵌套值", parsed.selectFirst("table table td").text());
            assertTrue(hybrid.contains("<table>"));
            assertTrue(hybrid.contains("<img src=\"https://example.com/cell.png\""));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * 验证真实 XLSX 的每个工作表被表达为 SHEET 类型 SectionBlock。
     *
     * @throws Exception 文件创建或解析失败
     */
    @Test
    public void mapsRealXlsxSheetsToSectionBlocks() throws Exception {
        Path file = Files.createTempFile("agents-flex-sections-", ".xlsx");
        try {
            writeWorkbook(file);
            ExtractedDocument document = new ExcelExtractor().extractDocument(
                new FileDocumentSource(file.toFile()), new DataUriDocumentImagePublisher());

            assertEquals(2, document.getBlocks().size());
            SectionBlock first = (SectionBlock) document.getBlocks().get(0);
            SectionBlock second = (SectionBlock) document.getBlocks().get(1);
            assertEquals(SectionType.SHEET, first.getType());
            assertEquals("经营数据", first.getTitle());
            assertTrue(first.getContent().get(0) instanceof TableBlock);
            assertEquals("空白页", second.getTitle());
            assertTrue(second.getContent().get(0) instanceof ParagraphBlock);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * 创建包含丰富结构的真实 DOCX 文件。
     */
    private void writeRichDocx(Path file) throws Exception {
        byte[] png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        try (XWPFDocument document = new XWPFDocument(); OutputStream output = Files.newOutputStream(file)) {
            XWPFParagraph heading = document.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("产品说明");

            XWPFParagraph paragraph = document.createParagraph();
            paragraph.createRun().setText("普通");
            XWPFRun bold = paragraph.createRun();
            bold.setBold(true);
            bold.setText("加粗");
            XWPFRun italic = paragraph.createRun();
            italic.setItalic(true);
            italic.setText("斜体");
            paragraph.createHyperlinkRun("https://agentsflex.com").setText("官网");

            XWPFParagraph listOne = document.createParagraph();
            listOne.setNumID(BigInteger.ONE);
            listOne.createRun().setText("第一项");
            XWPFParagraph listTwo = document.createParagraph();
            listTwo.setNumID(BigInteger.ONE);
            listTwo.createRun().setText("第二项");

            XWPFTable table = document.createTable(1, 1);
            table.getRow(0).getCell(0).setText("第一段");
            table.getRow(0).getCell(0).addParagraph().createRun().setText("第二段");
            XWPFRun imageRun = table.getRow(0).getCell(0).addParagraph().createRun();
            imageRun.addPicture(new ByteArrayInputStream(png), Document.PICTURE_TYPE_PNG,
                "cell.png", Units.toEMU(1), Units.toEMU(1));
            document.write(output);
        }
    }

    /**
     * 创建包含两个工作表和合并区域的真实 XLSX 文件。
     */
    private void writeWorkbook(Path file) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream output = Files.newOutputStream(file)) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("经营数据");
            sheet.createRow(0).createCell(0).setCellValue("标题");
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 1));
            workbook.createSheet("空白页");
            workbook.write(output);
        }
    }

    /**
     * 统计元素的直接子元素中指定标签的数量，避免将嵌套表格内容计入父单元格断言。
     *
     * @param parent  父元素
     * @param tagName 标签名
     * @return 直接子元素数量
     */
    private int countDirectChildren(Element parent, String tagName) {
        int count = 0;
        for (Element child : parent.children()) {
            if (tagName.equalsIgnoreCase(child.tagName())) count++;
        }
        return count;
    }
}

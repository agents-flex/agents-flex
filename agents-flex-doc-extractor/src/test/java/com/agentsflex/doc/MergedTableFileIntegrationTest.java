package com.agentsflex.doc;

import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge;

import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 使用实际落盘的 DOCX、XLSX 和 HTML 文件验证合并单元格解析与三种渲染模式。
 *
 * <p>测试文件在测试期间写入系统临时目录，并通过正式的 FileDocumentSource 链路读取，
 * 避免只验证内存对象而遗漏文件格式序列化、重新打开和元数据识别问题。</p>
 */
public class MergedTableFileIntegrationTest {

    /**
     * 验证真实 DOCX 文件中的横向 gridSpan 和纵向 vMerge 能转换为 HTML 跨度属性。
     *
     * @throws Exception DOCX 创建、写入或解析失败
     */
    @Test
    public void extractsRealDocxFileWithHorizontalAndVerticalMerges() throws Exception {
        Path file = Files.createTempFile("agents-flex-merged-table-", ".docx");
        try {
            writeDocx(file);
            DocumentExtractionService service = new DocumentExtractionService();
            String hybrid = service.extract(file.toFile(), DocumentOutputFormat.MARKDOWN_WITH_HTML_TABLES);
            String html = service.extract(file.toFile(), DocumentOutputFormat.HTML);
            String markdown = service.extract(file.toFile(), DocumentOutputFormat.MARKDOWN);

            assertTrue(hybrid.startsWith("合并表格验证"));
            assertTrue(hybrid.contains("colspan=\"2\""));
            assertTrue(hybrid.contains("rowspan=\"2\""));
            assertFalse(hybrid.contains("| --- |"));
            assertTrue(markdown.contains("| --- | --- | --- |"));

            org.jsoup.nodes.Document parsed = Jsoup.parseBodyFragment(html);
            assertEquals("2", parsed.selectFirst("td:contains(季度汇总)").attr("colspan"));
            assertEquals("2", parsed.selectFirst("td:contains(华东)").attr("rowspan"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * 验证真实 XLSX 文件的合并区域和合并区域内部空行不会在解析时丢失。
     *
     * @throws Exception XLSX 创建、写入或解析失败
     */
    @Test
    public void extractsRealXlsxFileWithMergedRegionsAndEmptyRows() throws Exception {
        Path file = Files.createTempFile("agents-flex-merged-sheet-", ".xlsx");
        try {
            writeXlsx(file);
            String hybrid = new DocumentExtractionService().extract(
                file.toFile(), DocumentOutputFormat.MARKDOWN_WITH_HTML_TABLES);
            org.jsoup.nodes.Document parsed = Jsoup.parseBodyFragment(hybrid);

            assertTrue(hybrid.startsWith("### 经营数据"));
            Element title = parsed.selectFirst("td:contains(2026 年经营数据)");
            Element region = parsed.selectFirst("td:contains(华东)");
            assertEquals("3", title.attr("colspan"));
            assertEquals("3", region.attr("rowspan"));
            assertTrue(hybrid.contains("<tr><td></td><td></td></tr>"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * 验证真实 HTML 文件保留 rowspan/colspan，并且父表格不会错误收集嵌套表格行。
     *
     * @throws Exception HTML 文件写入或解析失败
     */
    @Test
    public void extractsRealHtmlFileWithoutPullingNestedRowsIntoParentTable() throws Exception {
        Path file = Files.createTempFile("agents-flex-merged-html-", ".html");
        try {
            String source = "<html><head><title>网页报表</title></head><body>"
                + "<h2>区域概览</h2><table><thead><tr><th rowspan='2'>区域</th>"
                + "<th colspan='2'>指标</th></tr><tr><th>收入</th><th>利润</th></tr></thead>"
                + "<tbody><tr><td>华东</td><td>100</td><td><table><tr><td>20</td></tr></table></td>"
                + "</tr></tbody></table><p>完成。</p></body></html>";
            Files.write(file, source.getBytes(StandardCharsets.UTF_8));

            String hybrid = new DocumentExtractionService().extract(
                file.toFile(), DocumentOutputFormat.MARKDOWN_WITH_HTML_TABLES);
            org.jsoup.nodes.Document parsed = Jsoup.parseBodyFragment(hybrid);

            assertTrue(hybrid.startsWith("# 网页报表"));
            assertTrue(hybrid.contains("## 区域概览"));
            assertEquals("2", parsed.selectFirst("th:contains(区域)").attr("rowspan"));
            assertEquals("2", parsed.selectFirst("th:contains(指标)").attr("colspan"));
            assertEquals(3, parsed.select("table > tbody > tr").size());
            assertTrue(hybrid.endsWith("完成。"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * 创建包含横向和纵向合并单元格的真实 DOCX 文件。
     *
     * @param file 目标 DOCX 路径
     * @throws Exception 文档创建或写入失败
     */
    private void writeDocx(Path file) throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             OutputStream output = Files.newOutputStream(file)) {
            document.createParagraph().createRun().setText("合并表格验证");
            XWPFTable table = document.createTable(3, 3);
            table.getRow(0).getCell(0).setText("季度汇总");
            table.getRow(0).getCell(1).setText("");
            table.getRow(0).getCell(2).setText("利润");
            setGridSpan(table.getRow(0).getCell(0), 2);
            table.getRow(0).getCtRow().removeTc(1);

            table.getRow(1).getCell(0).setText("华东");
            table.getRow(1).getCell(1).setText("100");
            table.getRow(1).getCell(2).setText("20");
            table.getRow(2).getCell(0).setText("");
            table.getRow(2).getCell(1).setText("120");
            table.getRow(2).getCell(2).setText("25");
            setVerticalMerge(table.getRow(1).getCell(0), "restart");
            setVerticalMerge(table.getRow(2).getCell(0), "continue");
            document.write(output);
        }
    }

    /**
     * 为 Word 单元格设置横向网格跨度。
     *
     * @param cell 目标 Word 单元格
     * @param span 横向跨度
     */
    private void setGridSpan(XWPFTableCell cell, int span) {
        CTTcPr properties = cell.getCTTc().isSetTcPr()
            ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        properties.addNewGridSpan().setVal(BigInteger.valueOf(span));
    }

    /**
     * 为 Word 单元格设置纵向合并 restart 或 continue 标记。
     *
     * @param cell  目标 Word 单元格
     * @param value WordprocessingML 合并标记值
     */
    private void setVerticalMerge(XWPFTableCell cell, String value) {
        CTTcPr properties = cell.getCTTc().isSetTcPr()
            ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
        properties.addNewVMerge().setVal(STMerge.Enum.forString(value));
    }

    /**
     * 创建包含横向、纵向合并及合并区域内部空行的真实 XLSX 文件。
     *
     * @param file 目标 XLSX 路径
     * @throws Exception 工作簿创建或写入失败
     */
    private void writeXlsx(Path file) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             OutputStream output = Files.newOutputStream(file)) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("经营数据");
            sheet.createRow(0).createCell(0).setCellValue("2026 年经营数据");
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 2));
            sheet.createRow(1).createCell(0).setCellValue("区域");
            sheet.getRow(1).createCell(1).setCellValue("收入");
            sheet.getRow(1).createCell(2).setCellValue("利润");
            sheet.createRow(2).createCell(0).setCellValue("华东");
            sheet.getRow(2).createCell(1).setCellValue(100);
            sheet.getRow(2).createCell(2).setCellValue(20);
            sheet.createRow(3);
            sheet.createRow(4).createCell(1).setCellValue(120);
            sheet.getRow(4).createCell(2).setCellValue(25);
            sheet.addMergedRegion(new CellRangeAddress(2, 4, 0, 0));
            workbook.write(output);
        }
    }
}

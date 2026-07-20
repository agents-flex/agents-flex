package com.agentsflex.core.file2text.extractor.impl;

import com.agentsflex.core.file2text.File2TextService;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.Assert.assertTrue;

public class ExcelExtractorTest {

    @Test
    public void shouldExtractLegacyXltTemplate() throws Exception {
        String text = new File2TextService().extractTextFromBytes(
            createWorkbook(new HSSFWorkbook()), "sample.xlt", "application/vnd.ms-excel");

        assertWorkbookText(text);
    }

    @Test
    public void shouldExtractMacroEnabledXltmTemplate() throws Exception {
        String text = new File2TextService().extractTextFromBytes(
            createWorkbook(new XSSFWorkbook()),
            "sample.xltm",
            "application/vnd.ms-excel.template.macroEnabled.12"
        );

        assertWorkbookText(text);
    }

    private byte[] createWorkbook(Workbook workbook) throws Exception {
        try (Workbook closeableWorkbook = workbook;
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            closeableWorkbook.createSheet("Template")
                .createRow(0)
                .createCell(0)
                .setCellValue("Template value");
            closeableWorkbook.write(outputStream);
            return outputStream.toByteArray();
        }
    }

    private void assertWorkbookText(String text) {
        assertTrue(text.contains("### Template"));
        assertTrue(text.contains("| Template value |"));
        assertTrue(text.contains("| --- |"));
    }
}

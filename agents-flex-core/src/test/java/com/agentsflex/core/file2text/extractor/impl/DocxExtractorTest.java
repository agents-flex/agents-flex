package com.agentsflex.core.file2text.extractor.impl;

import com.agentsflex.core.file2text.File2TextService;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static org.junit.Assert.assertTrue;

public class DocxExtractorTest {

    @Test
    public void shouldExtractImagesAndTablesAsMarkdown() throws Exception {
        byte[] docx = createDocx();

        String text = new File2TextService().extractTextFromBytes(
            docx,
            "sample.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        );

        assertTrue(text.contains("Document title"));
        assertTrue(text.contains("![Image](data:image/png;base64,"));
        assertTrue(text.contains("| Name | Score |"));
        assertTrue(text.contains("| --- | --- |"));
        assertTrue(text.contains("| Alice | 95 |"));
    }

    @Test
    public void shouldExtractMacroEnabledDocumentPackage() throws Exception {
        String text = new File2TextService().extractTextFromBytes(
            createDocx(),
            "sample.docm",
            "application/vnd.ms-word.document.macroEnabled.12"
        );

        assertTrue(text.contains("Document title"));
    }

    private byte[] createDocx() throws Exception {
        byte[] png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Document title");

            XWPFRun imageRun = document.createParagraph().createRun();
            imageRun.addPicture(
                new ByteArrayInputStream(png),
                Document.PICTURE_TYPE_PNG,
                "pixel.png",
                Units.toEMU(1),
                Units.toEMU(1)
            );

            XWPFTable table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("Name");
            table.getRow(0).getCell(1).setText("Score");
            table.getRow(1).getCell(0).setText("Alice");
            table.getRow(1).getCell(1).setText("95");

            document.write(outputStream);
            return outputStream.toByteArray();
        }
    }
}

package com.agentsflex.core.file2text.extractor.impl;

import com.agentsflex.core.file2text.File2TextService;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static org.junit.Assert.assertTrue;

public class PptxExtractorTest {

    @Test
    public void shouldExtractImagesAndTablesAsMarkdown() throws Exception {
        byte[] pptx = createPptx();

        String text = new File2TextService().extractTextFromBytes(
            pptx,
            "sample.pptx",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        );

        System.out.println(text);

        assertTrue(text.contains("--- Slide 1 ---"));
        assertTrue(text.contains("PPTX title"));
        assertTrue(text.contains("--- Slide 2 ---"));
        assertTrue(text.contains("![Image](data:image/png;base64,"));
        assertTrue(text.contains("--- Slide 3 ---"));
        assertTrue(text.contains("| Name | Score |"));
        assertTrue(text.contains("| --- | --- |"));
        assertTrue(text.contains("| Alice | 95 |"));
    }

    @Test
    public void shouldExtractMacroEnabledPresentationPackage() throws Exception {
        String text = new File2TextService().extractTextFromBytes(
            createPptx(),
            "sample.pptm",
            "application/vnd.ms-powerpoint.presentation.macroEnabled.12"
        );

        assertTrue(text.contains("PPTX title"));
    }

    private byte[] createPptx() throws Exception {
        byte[] png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

        try (XMLSlideShow slideShow = new XMLSlideShow();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            XSLFSlide textSlide = slideShow.createSlide();
            XSLFTextBox textBox = textSlide.createTextBox();
            textBox.setText("PPTX title");

            XSLFSlide imageSlide = slideShow.createSlide();
            imageSlide.createPicture(slideShow.addPicture(png, PictureData.PictureType.PNG));

            XSLFSlide tableSlide = slideShow.createSlide();
            XSLFTable table = tableSlide.createTable(2, 2);
            table.getCell(0, 0).setText("Name");
            table.getCell(0, 1).setText("Score");
            table.getCell(1, 0).setText("Alice");
            table.getCell(1, 1).setText("95");

            slideShow.write(outputStream);
            return outputStream.toByteArray();
        }
    }
}

package com.agentsflex.core.file2text.extractor.impl;

import com.agentsflex.core.file2text.File2TextService;
import com.agentsflex.core.file2text.source.ByteArrayDocumentSource;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTable;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.apache.poi.sl.usermodel.PictureData;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PptExtractorTest {

    @Test
    public void shouldSupportLegacyPptButNotPptx() {
        PptExtractor extractor = new PptExtractor();

        assertTrue(extractor.supports(new ByteArrayDocumentSource(new byte[0], "sample.ppt")));
        assertTrue(extractor.supports(new ByteArrayDocumentSource(
            new byte[0], "sample", "application/vnd.ms-powerpoint")));
        assertFalse(extractor.supports(new ByteArrayDocumentSource(new byte[0], "sample.pptx")));
    }

    @Test
    public void shouldExtractTextFromLegacyPpt() throws Exception {
        byte[] ppt = createPpt();

        String text = new File2TextService().extractTextFromBytes(
            ppt, "sample.ppt", "application/vnd.ms-powerpoint");

        assertTrue(text.contains("--- Slide 1 ---"));
        assertTrue(text.contains("Legacy PPT title"));
        assertTrue(text.contains("--- Slide 2 ---"));
        assertTrue(text.contains("![Image](data:image/png;base64,"));
        assertTrue(text.contains("--- Slide 3 ---"));
        assertTrue(text.contains("| Name | Score |"));
        assertTrue(text.contains("| --- | --- |"));
        assertTrue(text.contains("| Alice | 95 |"));
    }

    private byte[] createPpt() throws Exception {
        System.setProperty("java.awt.headless", "true");
        try (HSLFSlideShow slideShow = new HSLFSlideShow();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            HSLFSlide textSlide = slideShow.createSlide();
            HSLFTextBox textBox = new HSLFTextBox();
            textBox.setText("Legacy PPT title");
            textSlide.addShape(textBox);

            HSLFSlide imageSlide = slideShow.createSlide();
            byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
            imageSlide.createPicture(slideShow.addPicture(png, PictureData.PictureType.PNG));

            HSLFSlide tableSlide = slideShow.createSlide();
            HSLFTable table = tableSlide.createTable(2, 2);
            table.getCell(0, 0).setText("Name");
            table.getCell(0, 1).setText("Score");
            table.getCell(1, 0).setText("Alice");
            table.getCell(1, 1).setText("95");

            slideShow.write(outputStream);
            return outputStream.toByteArray();
        }
    }
}

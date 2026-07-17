package com.agentsflex.core.file2text.extractor.impl;

import com.agentsflex.core.file2text.File2TextService;
import com.agentsflex.core.file2text.source.ByteArrayDocumentSource;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public class PdfTextExtractorTest {

    @Test
    public void shouldSupportParameterizedMimeType() {
        PdfTextExtractor extractor = new PdfTextExtractor();

        assertTrue(extractor.supports(new ByteArrayDocumentSource(
            new byte[0], "sample", "Application/PDF; charset=binary")));
    }

    @Test
    public void shouldExtractPagesAndImagesAsMarkdown() throws Exception {
        byte[] pdf = createPdf();

        String text = new File2TextService().extractTextFromBytes(pdf, "sample.pdf", "application/pdf");

        assertTrue(text.contains("--- Page 1 ---"));
        assertTrue(text.contains("PDF first page"));
        assertTrue(text.contains("![Image](data:image/png;base64,"));
        assertTrue(text.contains("--- Page 2 ---"));
        assertTrue(text.contains("PDF second page"));
    }

    @Test
    public void shouldRenderUrlReturnedByExtractedImageHandler() throws Exception {
        File2TextService service = new File2TextService(
            (bytes, mimeType, fileName) -> "https://cdn.example.com/pdf-image.png");

        String text = service.extractTextFromBytes(createPdf(), "sample.pdf", "application/pdf");

        assertTrue(text.contains("![Image](https://cdn.example.com/pdf-image.png)"));
        assertFalse(text.contains(";base64,"));
    }

    private byte[] createPdf() throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            PDPage firstPage = new PDPage();
            document.addPage(firstPage);

            BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, 0x00FF00);
            PDImageXObject pdfImage = LosslessFactory.createFromImage(document, image);

            try (PDPageContentStream content = new PDPageContentStream(document, firstPage)) {
                content.beginText();
                content.setFont(PDType1Font.HELVETICA, 12);
                content.newLineAtOffset(72, 720);
                content.showText("PDF first page");
                content.endText();
                content.drawImage(pdfImage, 72, 650, 20, 20);
            }

            PDPage secondPage = new PDPage();
            document.addPage(secondPage);
            try (PDPageContentStream content = new PDPageContentStream(document, secondPage)) {
                content.beginText();
                content.setFont(PDType1Font.HELVETICA, 12);
                content.newLineAtOffset(72, 720);
                content.showText("PDF second page");
                content.endText();
            }

            document.save(outputStream);
            return outputStream.toByteArray();
        }
    }
}

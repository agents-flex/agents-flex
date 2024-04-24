package com.agentsflex.document.parser;

import com.agentsflex.document.Document;
import com.agentsflex.document.DocumentParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.io.InputStream;

public class PdfBoxDocumentParser implements DocumentParser {
    @Override
    public Document parse(InputStream stream) {
        try (PDDocument pdfDocument = PDDocument.load(stream)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(pdfDocument);
            return new Document(text);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}

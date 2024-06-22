package com.agentsflex.document.parser;

import com.agentsflex.core.document.Document;
import com.agentsflex.core.document.DocumentParser;
import org.apache.poi.extractor.ExtractorFactory;
import org.apache.poi.extractor.POITextExtractor;

import java.io.IOException;
import java.io.InputStream;

public class PoiDocumentParser implements DocumentParser {
    @Override
    public Document parse(InputStream stream) {
        try (POITextExtractor extractor = ExtractorFactory.createExtractor(stream)) {
            String text = extractor.getText();
            return new Document(text);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}

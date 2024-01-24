package com.agentsflex.document.parser;

import com.agentsflex.document.Document;
import com.agentsflex.document.Parser;
import org.apache.poi.extractor.ExtractorFactory;
import org.apache.poi.extractor.POITextExtractor;

import java.io.IOException;
import java.io.InputStream;

public class PoiParser implements Parser {
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

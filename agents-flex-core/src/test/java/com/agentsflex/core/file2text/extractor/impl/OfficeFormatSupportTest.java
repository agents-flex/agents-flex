package com.agentsflex.core.file2text.extractor.impl;

import com.agentsflex.core.file2text.source.ByteArrayDocumentSource;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class OfficeFormatSupportTest {

    @Test
    public void shouldSupportMacroEnabledWordFormats() {
        DocxExtractor extractor = new DocxExtractor();

        assertTrue(extractor.supports(source("sample.docm")));
        assertTrue(extractor.supports(source("sample.dotm")));
        assertTrue(extractor.supports(source(
            "sample", "application/vnd.ms-word.document.macroEnabled.12; charset=binary")));
        assertTrue(extractor.supports(source(
            "sample", "application/vnd.ms-word.template.macroEnabled.12")));
    }

    @Test
    public void shouldSupportMacroEnabledPowerPointFormats() {
        PptxExtractor extractor = new PptxExtractor();

        assertTrue(extractor.supports(source("sample.pptm")));
        assertTrue(extractor.supports(source("sample.ppsm")));
        assertTrue(extractor.supports(source("sample.potm")));
        assertTrue(extractor.supports(source(
            "sample", "application/vnd.ms-powerpoint.presentation.macroEnabled.12")));
        assertTrue(extractor.supports(source(
            "sample", "application/vnd.ms-powerpoint.slideshow.macroEnabled.12")));
        assertTrue(extractor.supports(source(
            "sample", "application/vnd.ms-powerpoint.template.macroEnabled.12")));
    }

    private ByteArrayDocumentSource source(String fileName) {
        return source(fileName, null);
    }

    private ByteArrayDocumentSource source(String fileName, String mimeType) {
        return new ByteArrayDocumentSource(new byte[0], fileName, mimeType);
    }
}

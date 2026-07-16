package com.agentsflex.core.file2text.extractor.impl;

import com.agentsflex.core.file2text.File2TextService;
import com.agentsflex.core.file2text.source.ByteArrayDocumentSource;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PlainTextExtractorTest {

    @Test
    public void shouldExtractTsvContent() {
        byte[] data = "name\tscore\nAlice\t95".getBytes(StandardCharsets.UTF_8);

        String text = new File2TextService().extractTextFromBytes(
            data, "scores.tsv", "text/tab-separated-values");

        assertEquals("name\tscore\nAlice\t95", text.trim());
    }

    @Test
    public void shouldSupportCommonCodeAndConfigFiles() {
        PlainTextExtractor extractor = new PlainTextExtractor();
        String[] fileNames = {
            "Main.java", "app.py", "index.tsx", "main.go", "lib.rs", "query.sql",
            "application.toml", "service.ini", ".env", ".gitignore",
            "Dockerfile", "Makefile", "Jenkinsfile"
        };

        for (String fileName : fileNames) {
            assertTrue(fileName, extractor.supports(
                new ByteArrayDocumentSource(new byte[0], fileName, null)));
        }
    }
}

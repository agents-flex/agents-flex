package com.agentsflex.core.file2text.extractor.impl;

import com.agentsflex.core.file2text.source.ByteArrayDocumentSource;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HtmlExtractorTest {

    @Test
    public void shouldExtractStandardMarkdownTable() throws Exception {
        String html = "<html><body><table>"
            + "<tr><th>Name</th><th>Notes</th></tr>"
            + "<tr><td>Alice</td><td>A|B<br>C</td></tr>"
            + "</table></body></html>";

        String text = new HtmlExtractor().extractText(new ByteArrayDocumentSource(
            html.getBytes(StandardCharsets.UTF_8), "table.html", "text/html"));

        assertTrue(text.contains("| Name | Notes |"));
        assertTrue(text.contains("| --- | --- |"));
        assertTrue(text.contains("| Alice | A\\|B C |"));
        assertFalse(text.contains("[Table Start]"));
    }
}

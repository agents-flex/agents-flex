package com.agentsflex.doc;

import com.agentsflex.doc.extractor.DocumentExtractor;
import com.agentsflex.doc.extractor.ExtractorRegistry;
import com.agentsflex.doc.source.DocumentSource;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DocumentExtractionServiceTest {

    @Test
    public void staticFacadeExtractsPlainTextBytes() {
        String content = DocumentExtractors.extract(
            "hello doc extractor".getBytes(StandardCharsets.UTF_8),
            "sample.txt",
            "text/plain"
        );

        assertEquals("hello doc extractor", content.trim());
    }

    @Test
    public void usesExtractorOrderAndCleansUpSource() {
        ExtractorRegistry registry = new ExtractorRegistry();
        registry.register(new DocumentExtractor() {
            @Override
            public boolean supports(DocumentSource source) {
                return true;
            }

            @Override
            public String extractText(DocumentSource source) {
                return "custom content";
            }

            @Override
            public int getOrder() {
                return 0;
            }
        });

        AtomicBoolean cleaned = new AtomicBoolean();
        DocumentSource source = new DocumentSource() {
            @Override
            public String getFileName() {
                return "sample.txt";
            }

            @Override
            public String getMimeType() {
                return "text/plain";
            }

            @Override
            public InputStream openStream() {
                return new ByteArrayInputStream(new byte[0]);
            }

            @Override
            public void cleanup() {
                cleaned.set(true);
            }
        };

        assertEquals("custom content", new DocumentExtractionService(registry).extract(source));
        assertTrue(cleaned.get());
    }
}

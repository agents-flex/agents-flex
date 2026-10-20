package com.agentsflex.doc;

import com.agentsflex.doc.extractor.DocumentExtractor;
import com.agentsflex.doc.extractor.ExtractorRegistry;
import com.agentsflex.doc.source.DocumentSource;
import org.junit.Test;
import com.sun.net.httpserver.HttpServer;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public class DocumentExtractionServiceTest {

    @Test
    public void extractsDocxFromEncodedLocalUrlWithOriginalMetadata() throws Exception {
        Path directory = Files.createTempDirectory("文档 URL ");
        Path file = directory.resolve("opaque-object-key");
        try {
            try (XWPFDocument document = new XWPFDocument();
                 java.io.OutputStream output = Files.newOutputStream(file)) {
                document.createParagraph().createRun().setText("合同正文：文档读取验证。");
                document.write(output);
            }
            String content = DocumentExtractors.extractFromUrl(file.toUri().toASCIIString(),
                "合同.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            assertTrue(content.contains("合同正文：文档读取验证。"));
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void extractsHttpUrlWithoutHeadWhenMetadataIsProvided() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger heads = new AtomicInteger();
        AtomicInteger gets = new AtomicInteger();
        server.createContext("/signed-object", exchange -> {
            try {
                if ("HEAD".equals(exchange.getRequestMethod())) {
                    heads.incrementAndGet();
                    exchange.sendResponseHeaders(403, -1);
                } else {
                    gets.incrementAndGet();
                    byte[] bytes = "URL document content".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            String content = DocumentExtractors.extractFromUrl(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/signed-object?signature=test",
                "正文.txt", "text/plain");
            assertEquals("URL document content", content.trim());
            assertEquals(0, heads.get());
            assertEquals(1, gets.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void rejectsUnsupportedUrlSchemes() {
        assertThrows(IllegalArgumentException.class,
            () -> DocumentExtractors.extractFromUrl("ftp://example.com/document.txt"));
    }

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

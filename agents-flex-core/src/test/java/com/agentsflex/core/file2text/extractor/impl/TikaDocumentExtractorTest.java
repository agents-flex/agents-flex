package com.agentsflex.core.file2text.extractor.impl;

import com.agentsflex.core.file2text.File2TextService;
import com.agentsflex.core.file2text.source.ByteArrayDocumentSource;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TikaDocumentExtractorTest {

    @Test
    public void shouldSupportConfiguredLongTailFormats() {
        TikaDocumentExtractor extractor = new TikaDocumentExtractor();
        String[] fileNames = {
            "sample.rtf",
            "sample.odt", "sample.ods", "sample.odp", "sample.odg",
            "sample.ott", "sample.ots", "sample.otp", "sample.otg",
            "sample.eml", "sample.msg", "sample.epub", "sample.xlsb",
            "sample.pages", "sample.numbers", "sample.key",
            "sample.zip", "sample.tar", "sample.tgz", "sample.gz",
            "sample.bz2", "sample.xz", "sample.7z", "sample.rar"
        };

        for (String fileName : fileNames) {
            assertTrue(fileName, extractor.supports(
                new ByteArrayDocumentSource(new byte[0], fileName, null)));
        }
    }

    @Test
    public void shouldLoadXlsbAndIWorkParsers() {
        Set<MediaType> mediaTypes = new AutoDetectParser().getSupportedTypes(new ParseContext());

        assertTrue(mediaTypes.contains(MediaType.parse(
            "application/vnd.ms-excel.sheet.binary.macroenabled.12")));
        assertTrue(mediaTypes.contains(MediaType.parse("application/vnd.apple.pages")));
        assertTrue(mediaTypes.contains(MediaType.parse("application/vnd.apple.numbers")));
        assertTrue(mediaTypes.contains(MediaType.parse("application/vnd.apple.keynote")));
    }

    @Test
    public void shouldExtractTextFromZipArchive() throws Exception {
        byte[] archive = createZip(new Entry("notes.txt", "Archive entry content"));

        String text = extract(archive, "sample.zip", "application/zip");

        assertTrue(text.contains("notes.txt"));
        assertTrue(text.contains("Archive entry content"));
    }

    @Test
    public void shouldExtractRtf() {
        String rtf = "{\\rtf1\\ansi\\deff0 {\\fonttbl {\\f0 Arial;}}"
            + "\\f0\\fs24 RTF document content\\par}";

        String text = extract(rtf.getBytes(StandardCharsets.US_ASCII), "sample.rtf", "application/rtf");

        assertTrue(text.contains("RTF document content"));
    }

    @Test
    public void shouldExtractOpenDocumentTextAndTable() throws Exception {
        String contentXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<office:document-content "
            + "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" "
            + "xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" "
            + "xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\">"
            + "<office:body><office:text>"
            + "<text:h>OpenDocument title</text:h>"
            + "<text:p>OpenDocument paragraph</text:p>"
            + "<table:table table:name=\"Scores\">"
            + "<table:table-row><table:table-cell><text:p>Name</text:p></table:table-cell>"
            + "<table:table-cell><text:p>Score</text:p></table:table-cell></table:table-row>"
            + "<table:table-row><table:table-cell><text:p>Alice</text:p></table:table-cell>"
            + "<table:table-cell><text:p>95</text:p></table:table-cell></table:table-row>"
            + "</table:table></office:text></office:body></office:document-content>";

        byte[] odt = createZip(
            new Entry("mimetype", "application/vnd.oasis.opendocument.text"),
            new Entry("content.xml", contentXml),
            new Entry("META-INF/manifest.xml", "<?xml version=\"1.0\"?>"
                + "<manifest:manifest xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\">"
                + "<manifest:file-entry manifest:full-path=\"/\" "
                + "manifest:media-type=\"application/vnd.oasis.opendocument.text\"/>"
                + "</manifest:manifest>")
        );

        String text = extract(odt, "sample.odt", "application/vnd.oasis.opendocument.text");

        assertTrue(text.contains("OpenDocument title"));
        assertTrue(text.contains("OpenDocument paragraph"));
        assertTrue(text.contains("Name"));
        assertTrue(text.contains("Alice"));
        assertTrue(text.contains("| --- | --- |"));
    }

    @Test
    public void shouldUseConfiguredHandlerForEmbeddedOpenDocumentImage() throws Exception {
        byte[] image = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        String contentXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<office:document-content "
            + "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" "
            + "xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" "
            + "xmlns:draw=\"urn:oasis:names:tc:opendocument:xmlns:drawing:1.0\" "
            + "xmlns:xlink=\"http://www.w3.org/1999/xlink\">"
            + "<office:body><office:text><text:p>Before image</text:p>"
            + "<draw:frame><draw:image xlink:href=\"Pictures/example.png\"/></draw:frame>"
            + "</office:text></office:body></office:document-content>";
        byte[] odt = createZip(
            new Entry("mimetype", "application/vnd.oasis.opendocument.text"),
            new Entry("content.xml", contentXml),
            new Entry("META-INF/manifest.xml", "<?xml version=\"1.0\"?>"
                + "<manifest:manifest xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\">"
                + "<manifest:file-entry manifest:full-path=\"/\" "
                + "manifest:media-type=\"application/vnd.oasis.opendocument.text\"/>"
                + "<manifest:file-entry manifest:full-path=\"Pictures/example.png\" "
                + "manifest:media-type=\"image/png\"/>"
                + "</manifest:manifest>"),
            new Entry("Pictures/example.png", image)
        );
        AtomicReference<String> mimeType = new AtomicReference<>();
        AtomicReference<String> fileName = new AtomicReference<>();
        AtomicReference<byte[]> imageBytes = new AtomicReference<>();
        File2TextService service = new File2TextService((data, type, name) -> {
            imageBytes.set(data);
            mimeType.set(type);
            fileName.set(name);
            return "https://cdn.example.com/example.png";
        });

        String text = service.extractTextFromBytes(odt, "sample.odt",
            "application/vnd.oasis.opendocument.text");

        assertArrayEquals(image, imageBytes.get());
        assertEquals("image/png", mimeType.get());
        assertTrue(fileName.get().endsWith("example.png"));
        assertTrue(text.contains("![Image](https://cdn.example.com/example.png)"));
    }

    @Test
    public void shouldExtractEml() {
        String eml = "From: sender@example.com\r\n"
            + "To: receiver@example.com\r\n"
            + "Subject: Example message\r\n"
            + "MIME-Version: 1.0\r\n"
            + "Content-Type: text/plain; charset=UTF-8\r\n"
            + "\r\nEmail body content\r\n";

        String text = extract(eml.getBytes(StandardCharsets.UTF_8), "sample.eml", "message/rfc822");

        assertTrue(text.contains("Example message"));
        assertTrue(text.contains("Email body content"));
    }

    @Test
    public void shouldExtractEpub() throws Exception {
        byte[] epub = createZip(
            new Entry("mimetype", "application/epub+zip"),
            new Entry("META-INF/container.xml", "<?xml version=\"1.0\"?>"
                + "<container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\" version=\"1.0\">"
                + "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" "
                + "media-type=\"application/oebps-package+xml\"/></rootfiles></container>"),
            new Entry("OEBPS/content.opf", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\" unique-identifier=\"id\">"
                + "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                + "<dc:title>Example EPUB</dc:title><dc:identifier id=\"id\">book-1</dc:identifier>"
                + "<dc:language>en</dc:language></metadata>"
                + "<manifest><item id=\"chapter\" href=\"chapter.xhtml\" "
                + "media-type=\"application/xhtml+xml\"/></manifest>"
                + "<spine><itemref idref=\"chapter\"/></spine></package>"),
            new Entry("OEBPS/chapter.xhtml", "<html xmlns=\"http://www.w3.org/1999/xhtml\">"
                + "<head><title>Chapter</title></head><body>"
                + "<h1>EPUB chapter</h1><p>Electronic book content</p>"
                + "</body></html>")
        );

        String text = extract(epub, "sample.epub", "application/epub+zip");

        assertTrue(text.contains("EPUB chapter"));
        assertTrue(text.contains("Electronic book content"));
    }

    private String extract(byte[] data, String fileName, String mimeType) {
        return new File2TextService().extractTextFromBytes(data, fileName, mimeType);
    }

    private byte[] createZip(Entry... entries) throws Exception {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(outputStream)) {
            for (Entry entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.name));
                zip.write(entry.content);
                zip.closeEntry();
            }
            zip.finish();
            return outputStream.toByteArray();
        }
    }

    private static class Entry {
        private final String name;
        private final byte[] content;

        private Entry(String name, String content) {
            this(name, content.getBytes(StandardCharsets.UTF_8));
        }

        private Entry(String name, byte[] content) {
            this.name = name;
            this.content = content;
        }
    }
}

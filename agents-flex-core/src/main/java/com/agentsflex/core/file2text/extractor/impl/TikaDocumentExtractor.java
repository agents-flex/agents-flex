/*
 *  Copyright (c) 2023-2026, Agents-Flex (fuhai999@gmail.com).
 *  <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.agentsflex.core.file2text.extractor.impl;

import com.agentsflex.core.file2text.extractor.FileExtractor;
import com.agentsflex.core.file2text.extractor.MarkdownFormatter;
import com.agentsflex.core.file2text.handler.Base64ExtractedImageHandler;
import com.agentsflex.core.file2text.handler.ExtractedImageHandler;
import com.agentsflex.core.file2text.source.ByteArrayDocumentSource;
import com.agentsflex.core.file2text.source.DocumentSource;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.extractor.ParsingEmbeddedDocumentExtractor;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.ToXMLContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 基于 Apache Tika 的长尾文档提取器。
 * 支持 RTF、OpenDocument、邮件、EPUB、XLSB、iWork 和压缩包，输出 Markdown 风格文本。
 */
public class TikaDocumentExtractor implements FileExtractor {

    private static final Logger log = LoggerFactory.getLogger(TikaDocumentExtractor.class);
    private static final int MAX_ARCHIVE_DEPTH = 3;
    private static final int MAX_ARCHIVE_ENTRIES = 100;
    private static final long MAX_ARCHIVE_ENTRY_BYTES = 20L * 1024 * 1024;
    private static final long MAX_ARCHIVE_TOTAL_BYTES = 100L * 1024 * 1024;
    private static final Set<String> ARCHIVE_EXTENSIONS = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList("zip", "tar", "tgz", "gz", "bz2", "xz", "7z", "rar")));

    private static final Set<String> SUPPORTED_EXTENSIONS = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList(
            "rtf",
            "odt", "ods", "odp", "odg", "ott", "ots", "otp", "otg",
            "fodt", "fods", "fodp", "fodg",
            "eml", "msg",
            "epub",
            "xlsb",
            "pages", "numbers", "key",
            "zip", "tar", "tgz", "gz", "bz2", "xz", "7z", "rar"
        ))
    );

    private static final Set<String> SUPPORTED_MIME_TYPES = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList(
            "application/rtf", "text/rtf",
            "application/vnd.oasis.opendocument.text",
            "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.oasis.opendocument.presentation",
            "application/vnd.oasis.opendocument.graphics",
            "application/vnd.oasis.opendocument.text-template",
            "application/vnd.oasis.opendocument.spreadsheet-template",
            "application/vnd.oasis.opendocument.presentation-template",
            "application/vnd.oasis.opendocument.graphics-template",
            "message/rfc822", "application/vnd.ms-outlook",
            "application/epub+zip",
            "application/vnd.ms-excel.sheet.binary.macroenabled.12",
            "application/vnd.apple.pages", "application/vnd.apple.numbers", "application/vnd.apple.keynote",
            "application/x-iwork-pages-sffpages",
            "application/x-iwork-numbers-sffnumbers",
            "application/x-iwork-keynote-sffkey",
            "application/zip", "application/x-tar",
            "application/gzip", "application/x-gzip",
            "application/x-bzip2", "application/x-xz",
            "application/x-7z-compressed",
            "application/vnd.rar", "application/x-rar-compressed"
        ))
    );

    @Override
    public boolean supports(DocumentSource source) {
        String mimeType = source.getMimeType();
        if (mimeType != null) {
            String normalizedMimeType = mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (SUPPORTED_MIME_TYPES.contains(normalizedMimeType)) {
                return true;
            }
        }

        String extension = getExtension(source.getFileName());
        return extension != null && SUPPORTED_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
    }

    @Override
    public String extractText(DocumentSource source) throws IOException {
        return extractText(source, new Base64ExtractedImageHandler());
    }

    @Override
    public String extractText(DocumentSource source,
                              ExtractedImageHandler extractedImageHandler) throws IOException {
        Metadata metadata = new Metadata();
        if (source.getFileName() != null) {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, source.getFileName());
        }
        String contentType = resolveContentType(source);
        if (contentType != null) {
            metadata.set(HttpHeaders.CONTENT_TYPE, contentType);
        }

        ToXMLContentHandler handler = new ToXMLContentHandler();
        try (InputStream inputStream = source.openStream()) {
            AutoDetectParser parser = new AutoDetectParser();
            ParseContext context = new ParseContext();
            context.set(Parser.class, parser);
            context.set(EmbeddedDocumentExtractor.class,
                new LimitedEmbeddedDocumentExtractor(context, extractedImageHandler));
            parser.parse(inputStream, handler, metadata, context);
            byte[] xhtml = handler.toString().getBytes(StandardCharsets.UTF_8);
            return new HtmlExtractor().extractText(
                new ByteArrayDocumentSource(xhtml, "tika-output.xhtml", "application/xhtml+xml"));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to extract document with Apache Tika: " + e.getMessage(), e);
        }
    }

    @Override
    public int getOrder() {
        return 30;
    }

    private boolean shouldUseSourceMimeType(DocumentSource source) {
        String mimeType = source.getMimeType();
        if (mimeType == null) {
            return false;
        }

        String normalizedMimeType = mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if ("application/octet-stream".equals(normalizedMimeType)) {
            return false;
        }

        if ("application/zip".equals(normalizedMimeType)
            || "application/x-zip-compressed".equals(normalizedMimeType)) {
            String extension = getExtension(source.getFileName());
            return extension != null && ARCHIVE_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
        }
        return true;
    }

    private String resolveContentType(DocumentSource source) {
        String extension = getExtension(source.getFileName());
        if (extension != null) {
            switch (extension.toLowerCase(Locale.ROOT)) {
                case "pages":
                    return "application/vnd.apple.pages";
                case "numbers":
                    return "application/vnd.apple.numbers";
                case "key":
                    return "application/vnd.apple.keynote";
                case "xlsb":
                    return "application/vnd.ms-excel.sheet.binary.macroenabled.12";
                default:
                    break;
            }
        }
        return shouldUseSourceMimeType(source) ? source.getMimeType() : null;
    }

    private static class LimitedEmbeddedDocumentExtractor implements EmbeddedDocumentExtractor {
        private final ParsingEmbeddedDocumentExtractor delegate;
        private final ExtractedImageHandler extractedImageHandler;
        private int depth;
        private int entryCount;
        private long totalBytes;

        LimitedEmbeddedDocumentExtractor(ParseContext context,
                                          ExtractedImageHandler extractedImageHandler) {
            this.delegate = new ParsingEmbeddedDocumentExtractor(context);
            this.extractedImageHandler = extractedImageHandler;
            this.delegate.setWriteFileNameToContent(true);
        }

        @Override
        public boolean shouldParseEmbedded(Metadata metadata) {
            return depth < MAX_ARCHIVE_DEPTH
                && entryCount < MAX_ARCHIVE_ENTRIES
                && totalBytes < MAX_ARCHIVE_TOTAL_BYTES
                && delegate.shouldParseEmbedded(metadata);
        }

        @Override
        public void parseEmbedded(InputStream inputStream, ContentHandler handler,
                                  Metadata metadata, boolean outputHtml)
            throws SAXException, IOException {
            if (!shouldParseEmbedded(metadata)) {
                return;
            }

            byte[] data = readEntry(inputStream, metadata);
            if (data == null) {
                return;
            }

            entryCount++;
            totalBytes += data.length;
            if (isImage(metadata)) {
                writeImage(data, handler, metadata);
                return;
            }

            depth++;
            try {
                delegate.parseEmbedded(new ByteArrayInputStream(data), handler, metadata, outputHtml);
            } finally {
                depth--;
            }
        }

        private boolean isImage(Metadata metadata) {
            String mimeType = metadata.get(HttpHeaders.CONTENT_TYPE);
            return mimeType != null
                && mimeType.toLowerCase(Locale.ROOT).startsWith("image/");
        }

        private void writeImage(byte[] data, ContentHandler handler, Metadata metadata)
            throws IOException, SAXException {
            String mimeType = metadata.get(HttpHeaders.CONTENT_TYPE);
            String fileName = metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY);
            String imageUrl = MarkdownFormatter.handleImage(extractedImageHandler, data,
                mimeType, fileName);
            String imageMarkdown = MarkdownFormatter.formatImage(imageUrl);
            if (!imageMarkdown.isEmpty()) {
                char[] markdown = ("\n" + imageMarkdown + "\n").toCharArray();
                handler.characters(markdown, 0, markdown.length);
            }
        }

        private byte[] readEntry(InputStream inputStream, Metadata metadata) throws IOException {
            long remainingTotal = MAX_ARCHIVE_TOTAL_BYTES - totalBytes;
            long limit = Math.min(MAX_ARCHIVE_ENTRY_BYTES, remainingTotal);
            if (limit <= 0) {
                return null;
            }

            try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                long size = 0;
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    size += read;
                    if (size > limit) {
                        log.warn("Skipping oversized archive entry: {}", metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY));
                        return null;
                    }
                    outputStream.write(buffer, 0, read);
                }
                return outputStream.toByteArray();
            }
        }
    }
}

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
import com.agentsflex.core.file2text.source.DocumentSource;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDInlineImage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * PDF 文本提取器
 * 支持 PDF 文本和内嵌图片的 Markdown 提取
 */
public class PdfTextExtractor implements FileExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfTextExtractor.class);
    private static final long MAX_MAIN_MEMORY_BYTES = 32L * 1024 * 1024;
    private static final long MAX_IMAGE_PIXELS = 50_000_000L;
    private static final Set<String> SUPPORTED_MIME_TYPES;
    private static final Set<String> SUPPORTED_EXTENSIONS;

    static {
        Set<String> mimeTypes = new HashSet<>();
        mimeTypes.add("application/pdf");
        SUPPORTED_MIME_TYPES = Collections.unmodifiableSet(mimeTypes);

        Set<String> extensions = new HashSet<>();
        extensions.add("pdf");
        SUPPORTED_EXTENSIONS = Collections.unmodifiableSet(extensions);
    }

    @Override
    public boolean supports(DocumentSource source) {
        String mimeType = source.getMimeType();
        String fileName = source.getFileName();

        if (mimeType != null) {
            String normalizedMimeType = mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (SUPPORTED_MIME_TYPES.contains(normalizedMimeType)) {
                return true;
            }
        }

        if (fileName != null) {
            String ext = getExtension(fileName);
            return ext != null && SUPPORTED_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT));
        }

        return false;
    }

    @Override
    public String extractText(DocumentSource source) throws IOException {
        return extractText(source, new Base64ExtractedImageHandler());
    }

    @Override
    public String extractText(DocumentSource source, ExtractedImageHandler extractedImageHandler) throws IOException {
        try (InputStream is = source.openStream();
             PDDocument doc = PDDocument.load(is, MemoryUsageSetting.setupMixed(MAX_MAIN_MEMORY_BYTES))) {
            PageContentExtractor extractor = new PageContentExtractor(extractedImageHandler);
            extractor.setSortByPosition(true);
            extractor.setLineSeparator("\n");
            extractor.setPageStart("");
            extractor.setPageEnd("");
            return extractor.getText(doc).trim();
        } catch (Exception e) {
            throw new IOException("Failed to extract PDF text: " + e.getMessage(), e);
        }
    }

    private static class PageContentExtractor extends PDFTextStripper {
        private final List<String> images = new ArrayList<>();
        private final Set<COSBase> seenImages = Collections.newSetFromMap(
            new IdentityHashMap<COSBase, Boolean>());
        private final ExtractedImageHandler extractedImageHandler;
        private Writer documentOutput;
        private StringWriter pageOutput;

        PageContentExtractor(ExtractedImageHandler extractedImageHandler) throws IOException {
            super();
            this.extractedImageHandler = extractedImageHandler;
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            documentOutput = getOutput();
            pageOutput = new StringWriter();
            output = pageOutput;
            images.clear();
            seenImages.clear();
            super.startPage(page);
        }

        @Override
        protected void endPage(PDPage page) throws IOException {
            super.endPage(page);
            String pageText = pageOutput.toString().trim();
            output = documentOutput;

            if (pageText.isEmpty() && images.isEmpty()) {
                return;
            }

            output.write("\n--- Page " + getCurrentPageNo() + " ---\n");
            if (!pageText.isEmpty()) {
                output.write(pageText);
                output.write('\n');
            }
            for (String image : images) {
                output.write('\n');
                output.write(MarkdownFormatter.formatImage(image));
                output.write('\n');
            }
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            if ("Do".equals(operator.getName()) && !operands.isEmpty() && operands.get(0) instanceof COSName) {
                PDResources resources = getResources();
                if (resources != null) {
                    PDXObject xObject = resources.getXObject((COSName) operands.get(0));
                    if (xObject instanceof PDImageXObject) {
                        captureImage((PDImageXObject) xObject);
                    }
                }
            } else if ("BI".equals(operator.getName())
                && operator.getImageParameters() != null
                && operator.getImageData() != null) {
                captureImage(new PDInlineImage(
                    operator.getImageParameters(), operator.getImageData(), getResources()));
            }

            super.processOperator(operator, operands);
        }

        private void captureImage(PDImage image) throws IOException {
            COSBase imageObject = image.getCOSObject();
            if (!seenImages.add(imageObject)) {
                return;
            }

            long pixels = (long) image.getWidth() * image.getHeight();
            if (pixels <= 0 || pixels > MAX_IMAGE_PIXELS) {
                log.warn("Skipping PDF image with unsafe dimensions: {}x{}", image.getWidth(), image.getHeight());
                return;
            }

            byte[] imageBytes;
            try {
                BufferedImage bufferedImage = image.getImage();
                if (bufferedImage == null) {
                    return;
                }
                try (ByteArrayOutputStream imageOutput = new ByteArrayOutputStream()) {
                    if (!ImageIO.write(bufferedImage, "png", imageOutput)) {
                        return;
                    }
                    imageBytes = imageOutput.toByteArray();
                }
            } catch (Exception e) {
                log.warn("Failed to extract an image from PDF: {}", e.toString());
                return;
            }

            String fileName = "page-" + getCurrentPageNo() + "-image-" + seenImages.size() + ".png";
            String imageUrl = MarkdownFormatter.handleImage(extractedImageHandler, imageBytes,
                "image/png", fileName);
            if (imageUrl != null && !imageUrl.trim().isEmpty()) {
                images.add(imageUrl);
            }
        }
    }

    @Override
    public int getOrder() {
        return 10;
    }

}

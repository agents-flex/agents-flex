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
import com.agentsflex.core.file2text.handler.Base64ExtractedImageHandler;
import com.agentsflex.core.file2text.handler.ExtractedImageHandler;
import com.agentsflex.core.file2text.source.DocumentSource;
import org.apache.poi.xslf.usermodel.*;
import org.apache.xmlbeans.XmlException;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * PPTX 文档提取器（.pptx）
 * 提取幻灯片中的标题、段落、图片和表格
 */
public class PptxExtractor implements FileExtractor {

    private static final Set<String> SUPPORTED_MIME_TYPES;
    private static final String MIME_PREFIX = "application/vnd.openxmlformats-officedocument.presentationml";
    private static final Set<String> SUPPORTED_EXTENSIONS;

    static {
        // 精确 MIME（可选）
        Set<String> mimeTypes = new HashSet<>();
        mimeTypes.add("application/vnd.openxmlformats-officedocument.presentationml.presentation");
        mimeTypes.add("application/vnd.openxmlformats-officedocument.presentationml.slideshow");
        mimeTypes.add("application/vnd.ms-powerpoint.presentation.macroenabled.12");
        mimeTypes.add("application/vnd.ms-powerpoint.slideshow.macroenabled.12");
        mimeTypes.add("application/vnd.ms-powerpoint.template.macroenabled.12");
        SUPPORTED_MIME_TYPES = Collections.unmodifiableSet(mimeTypes);

        // 支持的扩展名
        Set<String> extensions = new HashSet<>();
        extensions.add("pptx");
        extensions.add("ppsx");
        extensions.add("potx");
        extensions.add("pptm");
        extensions.add("ppsm");
        extensions.add("potm");
        SUPPORTED_EXTENSIONS = Collections.unmodifiableSet(extensions);
    }

    @Override
    public boolean supports(DocumentSource source) {
        String mimeType = source.getMimeType();
        String fileName = source.getFileName();
        String normalizedMimeType = mimeType != null
            ? mimeType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT)
            : null;

        // 1. MIME 精确匹配
        if (normalizedMimeType != null && SUPPORTED_MIME_TYPES.contains(normalizedMimeType)) {
            return true;
        }

        // 2. MIME 前缀匹配
        if (normalizedMimeType != null && normalizedMimeType.startsWith(MIME_PREFIX)) {
            return true;
        }

        // 3. 扩展名匹配
        if (fileName != null) {
            String ext = getExtension(fileName);
            if (ext != null && SUPPORTED_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }

        return false;
    }

    @Override
    public String extractText(DocumentSource source) throws IOException {
        return extractText(source, new Base64ExtractedImageHandler());
    }

    @Override
    public String extractText(DocumentSource source, ExtractedImageHandler extractedImageHandler) throws IOException {
        StringBuilder text = new StringBuilder();

        try (InputStream is = source.openStream();
             XMLSlideShow slideShow = new XMLSlideShow(is)) {

            List<XSLFSlide> slides = slideShow.getSlides();

            for (int i = 0; i < slides.size(); i++) {
                XSLFSlide slide = slides.get(i);
                text.append("\n--- Slide ").append(i + 1).append(" ---\n");

                extractShapes(slide.getShapes(), text, extractedImageHandler);
            }

        } catch (XmlException e) {
            throw new IOException("Invalid PPTX structure: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IOException("Failed to extract PPTX: " + e.getMessage(), e);
        }

        return text.toString().trim();
    }

    private void extractShapes(List<XSLFShape> shapes, StringBuilder text,
                               ExtractedImageHandler extractedImageHandler) throws IOException {
        for (XSLFShape shape : shapes) {
            if (shape instanceof XSLFTable) {
                extractTable((XSLFTable) shape, text);
            } else if (shape instanceof XSLFPictureShape) {
                extractPicture((XSLFPictureShape) shape, text, extractedImageHandler);
            } else if (shape instanceof XSLFTextShape) {
                String shapeText = ((XSLFTextShape) shape).getText();
                if (shapeText != null && !shapeText.trim().isEmpty()) {
                    text.append(shapeText.trim()).append('\n');
                }
            } else if (shape instanceof XSLFGroupShape) {
                extractShapes(((XSLFGroupShape) shape).getShapes(), text, extractedImageHandler);
            }
        }
    }

    private void extractPicture(XSLFPictureShape pictureShape, StringBuilder text,
                                ExtractedImageHandler extractedImageHandler) throws IOException {
        XSLFPictureData pictureData = pictureShape.getPictureData();
        if (pictureData == null) {
            return;
        }

        byte[] data = pictureData.getData();
        if (data == null || data.length == 0) {
            return;
        }

        String contentType = pictureData.getContentType();
        if (contentType == null || contentType.trim().isEmpty()) {
            contentType = "application/octet-stream";
        }
        String imageUrl = extractedImageHandler.handle(data, contentType, pictureData.getFileName());
        if (imageUrl != null && !imageUrl.trim().isEmpty()) {
            text.append("\n![Image](").append(imageUrl).append(")\n");
        }
    }

    private void extractTable(XSLFTable table, StringBuilder text) {
        int rowCount = table.getNumberOfRows();
        int columnCount = table.getNumberOfColumns();
        if (rowCount == 0 || columnCount == 0) {
            return;
        }

        text.append('\n');
        appendTableRow(table, 0, columnCount, text);
        text.append('|');
        for (int column = 0; column < columnCount; column++) {
            text.append(" --- |");
        }
        text.append('\n');

        for (int row = 1; row < rowCount; row++) {
            appendTableRow(table, row, columnCount, text);
        }
        text.append('\n');
    }

    private void appendTableRow(XSLFTable table, int row, int columnCount, StringBuilder text) {
        text.append('|');
        for (int column = 0; column < columnCount; column++) {
            XSLFTableCell cell = table.getCell(row, column);
            String value = cell != null ? cell.getText() : "";
            text.append(' ').append(escapeMarkdownCell(value)).append(" |");
        }
        text.append('\n');
    }

    private String escapeMarkdownCell(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
            .replace("|", "\\|")
            .replace("\r", " ")
            .replace("\n", " ")
            .trim();
    }

    @Override
    public int getOrder() {
        return 10;
    }

}

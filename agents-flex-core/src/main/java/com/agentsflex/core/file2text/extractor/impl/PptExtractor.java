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
import org.apache.poi.hslf.usermodel.HSLFGroupShape;
import org.apache.poi.hslf.usermodel.HSLFPictureData;
import org.apache.poi.hslf.usermodel.HSLFPictureShape;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTable;
import org.apache.poi.hslf.usermodel.HSLFTableCell;
import org.apache.poi.hslf.usermodel.HSLFTextShape;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * PPT 文档提取器（PowerPoint 97-2003 二进制格式）
 */
public class PptExtractor implements FileExtractor {

    private static final Set<String> SUPPORTED_MIME_TYPES = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList(
            "application/vnd.ms-powerpoint",
            "application/mspowerpoint",
            "application/powerpoint"
        ))
    );

    private static final Set<String> SUPPORTED_EXTENSIONS = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList("ppt", "pps", "pot"))
    );

    @Override
    public boolean supports(DocumentSource source) {
        String mimeType = source.getMimeType();
        if (mimeType != null && SUPPORTED_MIME_TYPES.contains(mimeType.trim().toLowerCase(Locale.ROOT))) {
            return true;
        }

        String extension = getExtension(source.getFileName());
        return extension != null && SUPPORTED_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
    }

    @Override
    public String extractText(DocumentSource source) throws IOException {
        return extractText(source, new Base64ExtractedImageHandler());
    }

    @Override
    public String extractText(DocumentSource source, ExtractedImageHandler extractedImageHandler) throws IOException {
        StringBuilder text = new StringBuilder();

        try (InputStream inputStream = source.openStream();
             HSLFSlideShow slideShow = new HSLFSlideShow(inputStream)) {
            List<HSLFSlide> slides = slideShow.getSlides();
            for (int i = 0; i < slides.size(); i++) {
                text.append("\n--- Slide ").append(i + 1).append(" ---\n");
                extractShapes(slides.get(i).getShapes(), text, extractedImageHandler);
            }
        } catch (Exception e) {
            throw new IOException("Failed to extract PPT: " + e.getMessage(), e);
        }

        return text.toString().trim();
    }

    private void extractShapes(List<HSLFShape> shapes, StringBuilder text,
                               ExtractedImageHandler extractedImageHandler) throws IOException {
        for (HSLFShape shape : shapes) {
            if (shape instanceof HSLFTable) {
                extractTable((HSLFTable) shape, text);
            } else if (shape instanceof HSLFPictureShape) {
                extractPicture((HSLFPictureShape) shape, text, extractedImageHandler);
            } else if (shape instanceof HSLFTextShape) {
                String shapeText = ((HSLFTextShape) shape).getText();
                if (shapeText != null && !shapeText.trim().isEmpty()) {
                    text.append(shapeText.trim()).append('\n');
                }
            } else if (shape instanceof HSLFGroupShape) {
                extractShapes(((HSLFGroupShape) shape).getShapes(), text, extractedImageHandler);
            }
        }
    }

    private void extractPicture(HSLFPictureShape pictureShape, StringBuilder text,
                                ExtractedImageHandler extractedImageHandler) throws IOException {
        HSLFPictureData pictureData = pictureShape.getPictureData();
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
        String extension = pictureData.getType() != null ? pictureData.getType().extension : "bin";
        String fileName = "image-" + pictureData.getIndex() + "." + extension;
        String imageUrl = extractedImageHandler.handle(data, contentType, fileName);
        if (imageUrl != null && !imageUrl.trim().isEmpty()) {
            text.append("\n![Image](").append(imageUrl).append(")\n");
        }
    }

    private void extractTable(HSLFTable table, StringBuilder text) {
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

    private void appendTableRow(HSLFTable table, int row, int columnCount, StringBuilder text) {
        text.append('|');
        for (int column = 0; column < columnCount; column++) {
            HSLFTableCell cell = table.getCell(row, column);
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

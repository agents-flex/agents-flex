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
import com.agentsflex.core.file2text.source.DocumentSource;
import org.apache.poi.xslf.usermodel.*;
import org.apache.xmlbeans.XmlException;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * PPTX 文档提取器（.pptx）
 * 提取幻灯片中的标题、段落、表格文本
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
        SUPPORTED_MIME_TYPES = Collections.unmodifiableSet(mimeTypes);

        // 支持的扩展名
        Set<String> extensions = new HashSet<>();
        extensions.add("pptx");
        extensions.add("ppsx");
        extensions.add("potx");
        SUPPORTED_EXTENSIONS = Collections.unmodifiableSet(extensions);
    }

    @Override
    public boolean supports(DocumentSource source) {
        String mimeType = source.getMimeType();
        String fileName = source.getFileName();

        // 1. MIME 精确匹配
        if (mimeType != null && SUPPORTED_MIME_TYPES.contains(mimeType)) {
            return true;
        }

        // 2. MIME 前缀匹配
        if (mimeType != null && mimeType.startsWith(MIME_PREFIX)) {
            return true;
        }

        // 3. 扩展名匹配
        if (fileName != null) {
            String ext = getExtension(fileName);
            if (ext != null && SUPPORTED_EXTENSIONS.contains(ext.toLowerCase())) {
                return true;
            }
        }

        return false;
    }

    @Override
    public String extractText(DocumentSource source) throws IOException {
        StringBuilder text = new StringBuilder();

        try (InputStream is = source.openStream();
             XMLSlideShow slideShow = new XMLSlideShow(is)) {

            List<XSLFSlide> slides = slideShow.getSlides();

            for (int i = 0; i < slides.size(); i++) {
                XSLFSlide slide = slides.get(i);
                text.append("\n--- Slide ").append(i + 1).append(" ---\n");

                // 提取所有形状中的文本
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape) {
                        XSLFTextShape textShape = (XSLFTextShape) shape;
                        String shapeText = textShape.getText();
                        if (shapeText != null && !shapeText.trim().isEmpty()) {
                            text.append(shapeText).append("\n");
                        }
                    }
                }

                // 可选：提取表格
                extractTablesFromSlide(slide, text);
            }

        } catch (XmlException e) {
            throw new IOException("Invalid PPTX structure: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IOException("Failed to extract PPTX: " + e.getMessage(), e);
        }

        return text.toString().trim();
    }

    /**
     * 提取幻灯片中的表格内容
     */
    private void extractTablesFromSlide(XSLFSlide slide, StringBuilder text) {
        for (XSLFShape shape : slide.getShapes()) {
            if (shape instanceof XSLFTable) {
                XSLFTable table = (XSLFTable) shape;
                text.append("\n[Table Start]\n");
                for (XSLFTableRow row : table.getRows()) {
                    List<String> cellTexts = new ArrayList<>();
                    for (XSLFTableCell cell : row.getCells()) {
                        String cellText = cell.getText();
                        cellTexts.add(cellText != null ? cellText.trim() : "");
                    }
                    text.append(String.join(" | ", cellTexts)).append("\n");
                }
                text.append("[Table End]\n");
            }
        }
    }

    @Override
    public int getOrder() {
        return 10;
    }

    private String getExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) return null;
        int lastDot = fileName.lastIndexOf('.');
        return fileName.substring(lastDot + 1);
    }
}

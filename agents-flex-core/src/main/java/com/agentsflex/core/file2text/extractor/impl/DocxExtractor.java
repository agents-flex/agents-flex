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
import org.apache.poi.xwpf.usermodel.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * DOCX 文档提取器（.docx, .dotx）
 * 支持段落、表格、列表文本提取
 */
public class DocxExtractor implements FileExtractor {

    private static final Set<String> KNOWN_MIME_TYPES;
    private static final String MIME_PREFIX = "application/vnd.openxmlformats-officedocument.wordprocessingml";
    private static final Set<String> SUPPORTED_EXTENSIONS;

    static {
        // 精确 MIME（可选）
        Set<String> mimeTypes = new HashSet<>();
        mimeTypes.add("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        KNOWN_MIME_TYPES = Collections.unmodifiableSet(mimeTypes);

        // 支持的扩展名
        Set<String> extensions = new HashSet<>();
        extensions.add("docx");
        extensions.add("dotx");
        SUPPORTED_EXTENSIONS = Collections.unmodifiableSet(extensions);
    }

    @Override
    public boolean supports(DocumentSource source) {
        String mimeType = source.getMimeType();
        String fileName = source.getFileName();

        // 1. MIME 精确匹配
        if (mimeType != null && KNOWN_MIME_TYPES.contains(mimeType)) {
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
             XWPFDocument document = new XWPFDocument(is)) {

            // 提取段落
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String paraText = getParagraphText(paragraph);
                if (paraText != null && !paraText.trim().isEmpty()) {
                    text.append(paraText).append("\n");
                }
            }

            // 提取表格
            for (XWPFTable table : document.getTables()) {
                text.append("\n[Table Start]\n");
                for (XWPFTableRow row : table.getRows()) {
                    List<String> cellTexts = row.getTableCells().stream()
                        .map(this::getCellText)
                        .map(String::trim)
                        .collect(Collectors.toList());
                    text.append(cellTexts).append("\n");
                }
                text.append("[Table End]\n\n");
            }

        } catch (Exception e) {
            throw new IOException("Failed to extract DOCX: " + e.getMessage(), e);
        }

        return text.toString().trim();
    }

    private String getParagraphText(XWPFParagraph paragraph) {
        StringBuilder text = new StringBuilder();
        for (XWPFRun run : paragraph.getRuns()) {
            String runText = run.text();
            if (runText != null) {
                text.append(runText);
            }
        }
        return text.length() > 0 ? text.toString() : null;
    }

    private String getCellText(XWPFTableCell cell) {
        String simpleText = cell.getText();
        if (simpleText != null && !simpleText.isEmpty()) {
            return simpleText;
        }
        StringBuilder text = new StringBuilder();
        for (XWPFParagraph p : cell.getParagraphs()) {
            String pt = getParagraphText(p);
            if (pt != null) {
                text.append(pt).append(" ");
            }
        }
        return text.toString().trim();
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

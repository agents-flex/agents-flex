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
import org.apache.poi.xwpf.usermodel.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * DOCX 文档提取器（.docx, .dotx）
 * 支持段落、表格、列表文本提取，以及嵌入图片处理
 */
public class DocxExtractor implements FileExtractor {

    private static final Set<String> KNOWN_MIME_TYPES;
    private static final String MIME_PREFIX = "application/vnd.openxmlformats-officedocument.wordprocessingml";
    private static final Set<String> SUPPORTED_EXTENSIONS;

    static {
        // 精确 MIME（可选）
        Set<String> mimeTypes = new HashSet<>();
        mimeTypes.add("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        mimeTypes.add("application/vnd.openxmlformats-officedocument.wordprocessingml.template");
        mimeTypes.add("application/vnd.ms-word.document.macroenabled.12");
        mimeTypes.add("application/vnd.ms-word.template.macroenabled.12");
        KNOWN_MIME_TYPES = Collections.unmodifiableSet(mimeTypes);

        // 支持的扩展名
        Set<String> extensions = new HashSet<>();
        extensions.add("docx");
        extensions.add("dotx");
        extensions.add("docm");
        extensions.add("dotm");
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
        if (normalizedMimeType != null && KNOWN_MIME_TYPES.contains(normalizedMimeType)) {
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
             XWPFDocument document = new XWPFDocument(is)) {

            // 按文档中的原始顺序提取段落和表格
            for (IBodyElement bodyElement : document.getBodyElements()) {
                if (bodyElement instanceof XWPFParagraph) {
                    String paragraphText = getParagraphText((XWPFParagraph) bodyElement, extractedImageHandler);
                    if (paragraphText != null && !paragraphText.trim().isEmpty()) {
                        text.append(paragraphText).append('\n');
                    }
                } else if (bodyElement instanceof XWPFTable) {
                    extractTable((XWPFTable) bodyElement, text, extractedImageHandler);
                }
            }

        } catch (Exception e) {
            throw new IOException("Failed to extract DOCX: " + e.getMessage(), e);
        }

        return text.toString().trim();
    }

    /**
     * 提取段落文本，包括其中嵌入的图片
     */
    private String getParagraphText(XWPFParagraph paragraph, ExtractedImageHandler extractedImageHandler) throws IOException {
        StringBuilder text = new StringBuilder();

        // 1. 提取普通文本 Run
        for (XWPFRun run : paragraph.getRuns()) {
            String runText = run.text();
            if (runText != null) {
                text.append(runText);
            }

            // 2. 提取图片 (Inline Images)
            // XWPFRun 可能包含多个图片，虽然常见是一个
            List<XWPFPicture> pictures = run.getEmbeddedPictures();
            if (pictures != null && !pictures.isEmpty()) {
                for (XWPFPicture picture : pictures) {
                    String imageUrl = processImage(picture, extractedImageHandler);
                    MarkdownFormatter.appendImage(text, imageUrl);
                }
            }
        }

        // 注意：有些复杂的图片可能是锚定的 (Anchored)，不在 Run 中，而在 Paragraph 的底层 XML 中
        // 如果需要更全面的支持，可以解析 paragraph.getCTP()，但通常 getEmbeddedPictures 覆盖了大部分场景

        return text.length() > 0 ? text.toString() : null;
    }

    /**
     * 提取单元格文本，递归处理段落和图片
     */
    private String getCellText(XWPFTableCell cell, ExtractedImageHandler extractedImageHandler) throws IOException {
        StringBuilder text = new StringBuilder();
        for (XWPFParagraph p : cell.getParagraphs()) {
            String pt = getParagraphText(p, extractedImageHandler);
            if (pt != null) {
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(pt);
            }
        }
        return text.toString().trim();
    }

    private void extractTable(XWPFTable table, StringBuilder text, ExtractedImageHandler extractedImageHandler) throws IOException {
        List<XWPFTableRow> rows = table.getRows();
        if (rows == null || rows.isEmpty()) {
            return;
        }

        List<List<String>> tableRows = new ArrayList<>();
        for (XWPFTableRow row : rows) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(getCellText(cell, extractedImageHandler));
            }
            tableRows.add(cells);
        }
        MarkdownFormatter.appendTable(text, tableRows);
    }

    private String processImage(XWPFPicture picture, ExtractedImageHandler extractedImageHandler) throws IOException {
        XWPFPictureData pictureData = picture.getPictureData();
        if (pictureData == null) {
            return null;
        }


        String mimeType = pictureData.getPackagePart().getContentType();
        if (mimeType == null || mimeType.trim().isEmpty()) {
            mimeType = "application/octet-stream";
        }

        byte[] data = pictureData.getData();
        if (data == null || data.length == 0) {
            return null;
        }
        return MarkdownFormatter.handleImage(extractedImageHandler, data, mimeType,
            pictureData.getFileName());
    }

    @Override
    public int getOrder() {
        return 10;
    }

}

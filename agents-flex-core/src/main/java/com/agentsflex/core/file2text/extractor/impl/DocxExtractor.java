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
import com.agentsflex.core.util.ImageUtil;
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
 * 支持段落、表格、列表文本提取，以及嵌入图片的 Base64 提取
 */
public class DocxExtractor implements FileExtractor {

    private static final Set<String> KNOWN_MIME_TYPES;
    private static final String MIME_PREFIX = "application/vnd.openxmlformats-officedocument.wordprocessingml";
    private static final Set<String> SUPPORTED_EXTENSIONS;

    static {
        // 精确 MIME（可选）
        Set<String> mimeTypes = new HashSet<>();
        mimeTypes.add("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        // 也可以添加 template 类型
        mimeTypes.add("application/vnd.openxmlformats-officedocument.wordprocessingml.template");
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
                    // 注意：这里直接 append List 可能会输出 [col1, col2] 格式，视需求而定
                    text.append(cellTexts).append("\n");
                }
                text.append("[Table End]\n\n");
            }

        } catch (Exception e) {
            throw new IOException("Failed to extract DOCX: " + e.getMessage(), e);
        }

        return text.toString().trim();
    }

    /**
     * 提取段落文本，包括其中嵌入的图片
     */
    private String getParagraphText(XWPFParagraph paragraph) {
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
                    String imageBase64 = extractImageAsBase64(picture);
                    if (imageBase64 != null) {
                        // 使用 Markdown 格式或自定义标签包裹图片
                        // 这里使用 Markdown 格式，方便后续 LLM 理解或前端渲染
                        text.append("\n![Image](").append(imageBase64).append(")\n");
                    }
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
    private String getCellText(XWPFTableCell cell) {
        StringBuilder text = new StringBuilder();
        for (XWPFParagraph p : cell.getParagraphs()) {
            String pt = getParagraphText(p);
            if (pt != null) {
                text.append(pt).append(" ");
            }
        }
        return text.toString().trim();
    }

    /**
     * 将图片转换为 Base64 Data URI
     */
    private String extractImageAsBase64(XWPFPicture picture) {
        try {
            XWPFPictureData pictureData = picture.getPictureData();
            if (pictureData == null) {
                return null;
            }


            String fileName = pictureData.getFileName();
            String extension = getExtension(fileName);

            // 不支持 wmf 文件格式
            if ("wmf".equalsIgnoreCase(extension) || "emf".equalsIgnoreCase(extension)) {
                return null;
            }

            String mimeType = ImageUtil.getMimeTypeFromExtension(extension);
            if (mimeType == null) {
                mimeType = "image/png"; // 默认
            }

            byte[] data = pictureData.getData();
            return ImageUtil.imageBytesToDataUri(data, mimeType);

        } catch (Exception e) {
            // 记录日志但不中断整个文档提取
            System.err.println("Failed to extract image: " + e.getMessage());
            return null;
        }
    }

    @Override
    public int getOrder() {
        return 10;
    }

}

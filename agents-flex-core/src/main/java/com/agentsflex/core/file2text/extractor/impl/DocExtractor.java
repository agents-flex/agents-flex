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
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.model.PicturesTable;
import org.apache.poi.hwpf.usermodel.CharacterRun;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Picture;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * DOC 文档提取器（.doc）
 * 支持旧版 Word 97-2003 格式
 * 支持段落文本提取以及嵌入图片的 Base64 提取
 */
public class DocExtractor implements FileExtractor {

    private static final Set<String> SUPPORTED_MIME_TYPES;
    private static final Set<String> SUPPORTED_EXTENSIONS;

    static {
        Set<String> mimeTypes = new HashSet<>();
        mimeTypes.add("application/msword");
        SUPPORTED_MIME_TYPES = Collections.unmodifiableSet(mimeTypes);

        Set<String> extensions = new HashSet<>();
        extensions.add("doc");
        extensions.add("dot");
        SUPPORTED_EXTENSIONS = Collections.unmodifiableSet(extensions);
    }

    @Override
    public boolean supports(DocumentSource source) {
        String mimeType = source.getMimeType();
        String fileName = source.getFileName();

        if (mimeType != null && SUPPORTED_MIME_TYPES.contains(mimeType)) {
            return true;
        }

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
        try (InputStream is = source.openStream();
             POIFSFileSystem fs = new POIFSFileSystem(is);
             HWPFDocument doc = new HWPFDocument(fs)) {

            StringBuilder text = new StringBuilder();
            Range range = doc.getRange();
            PicturesTable picturesTable = doc.getPicturesTable();

            // 遍历所有段落
            int numParagraphs = range.numParagraphs();
            for (int i = 0; i < numParagraphs; i++) {
                Paragraph paragraph = range.getParagraph(i);

                // 遍历段落中的每个 CharacterRun
                int numRuns = paragraph.numCharacterRuns();
                for (int j = 0; j < numRuns; j++) {
                    CharacterRun run = paragraph.getCharacterRun(j);

                    // 检查是否是图片占位符
                    // 在 HWPF 中，图片通常由一个特殊的 CharacterRun 表示，其中包含 Picture 对象
                    if (picturesTable.hasPicture(run)) {
                        Picture picture = picturesTable.extractPicture(run, true);
                        if (picture != null) {
                            String imageBase64 = convertPictureToBase64(picture);
                            if (imageBase64 != null) {
                                text.append("\n![Image](").append(imageBase64).append(")\n");
                            }
                        }
                    } else {
                        // 普通文本
                        String runText = run.text();
                        if (runText != null) {
                            // 清理一些常见的控制字符，但保留换行符逻辑由段落处理或手动添加
                            // HWPF 的 run.text() 通常不包含段落末尾的换行符，除非是特定情况
                            text.append(runText);
                        }
                    }
                }

                // 每个段落结束后添加换行符
                // 注意：HWPF 的 Paragraph 文本有时已经包含 \r，这里为了统一格式，追加 \n
                // 如果发现有重复换行，可以根据实际情况调整
                text.append("\n");
            }

            return text.toString().trim();

        } catch (Exception e) {
            throw new IOException("Failed to extract .doc file: " + e.getMessage(), e);
        }
    }

    /**
     * 将 HWPF Picture 对象转换为 Base64 Data URI
     */
    private String convertPictureToBase64(Picture picture) {
        try {
            if (picture == null) {
                return null;
            }

            byte[] data = picture.getContent();
            if (data == null || data.length == 0) {
                return null;
            }

            // 获取建议的文件扩展名或 MIME 类型
            // HWPF Picture 的 suggestFileExtension() 返回如 "wmf", "jpeg", "png" 等
            String ext = picture.suggestFileExtension();

            // 过滤不支持的格式，特别是 WMF/EMF，浏览器和大多数 LLM 无法直接渲染
            if ("wmf".equalsIgnoreCase(ext) || "emf".equalsIgnoreCase(ext)) {
                // 可选：记录日志说明跳过了 WMF 图片
                // System.err.println("Skipping unsupported WMF/EMF image in DOC");
                return null;
            }

            String mimeType = ImageUtil.getMimeTypeFromExtension(ext);
            if (mimeType == null) {
                // 如果无法识别类型，尝试默认 PNG 或 JPEG，或者跳过
                // 很多旧 DOC 图片是 JPEG 即使扩展名不明
                mimeType = "image/jpeg";
            }

            return ImageUtil.imageBytesToDataUri(data, mimeType);

        } catch (Exception e) {
            System.err.println("Failed to convert DOC image to Base64: " + e.getMessage());
            return null;
        }
    }


    @Override
    public int getOrder() {
        return 15; // 低于 .docx
    }

}

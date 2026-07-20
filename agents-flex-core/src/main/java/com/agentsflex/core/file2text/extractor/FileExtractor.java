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
package com.agentsflex.core.file2text.extractor;

import com.agentsflex.core.file2text.handler.ExtractedImageHandler;
import com.agentsflex.core.file2text.source.DocumentSource;

import java.io.IOException;
import java.util.Comparator;

public interface FileExtractor {

    Comparator<FileExtractor> ORDER_COMPARATOR =
        Comparator.comparingInt(FileExtractor::getOrder);

    /**
     * 判断该 Extractor 是否支持处理此文档
     */
    boolean supports(DocumentSource source);

    /**
     * 提取 Markdown 风格文本。表格和图片应使用 {@link MarkdownFormatter} 输出，
     * 以保持内置和自定义解析器的格式一致。
     */
    String extractText(DocumentSource source) throws IOException;

    /**
     * 使用指定的图片处理器提取文档内容。处理图片的解析器应通过
     * {@link MarkdownFormatter#handleImage(ExtractedImageHandler, byte[], String, String)}
     * 调用处理器并统一 MIME 类型、文件名和 Markdown 格式。
     * 不处理图片的自定义解析器只需实现 {@link #extractText(DocumentSource)}。
     */
    default String extractText(DocumentSource source,
                               ExtractedImageHandler extractedImageHandler) throws IOException {
        return extractText(source);
    }

    default int getOrder() {
        return 100;
    }

    default String getExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) return null;
        int lastDot = fileName.lastIndexOf('.');
        return fileName.substring(lastDot + 1).toLowerCase();
    }
}

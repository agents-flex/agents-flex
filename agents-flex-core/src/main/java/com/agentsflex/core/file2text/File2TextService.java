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
package com.agentsflex.core.file2text;


import com.agentsflex.core.file2text.extractor.FileExtractor;
import com.agentsflex.core.file2text.extractor.ExtractorRegistry;
import com.agentsflex.core.file2text.source.*;

import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

public class File2TextService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(File2TextService.class);
    private final ExtractorRegistry registry;

    public File2TextService() {
        this(new ExtractorRegistry());
    }

    public File2TextService(ExtractorRegistry registry) {
        this.registry = registry;
    }

    public ExtractorRegistry getRegistry() {
        return registry;
    }

    public String extractTextFromHttpUrl(String httpUrl) {
        return extractTextFromSource(new HttpDocumentSource(httpUrl));
    }

    public String extractTextFromHttpUrl(String httpUrl, String fileName) {
        return extractTextFromSource(new HttpDocumentSource(httpUrl, fileName));
    }

    public String extractTextFromHttpUrl(String httpUrl, String fileName, String mimeType) {
        return extractTextFromSource(new HttpDocumentSource(httpUrl, fileName, mimeType));
    }

    public String extractTextFromFile(File file) {
        return extractTextFromSource(new FileDocumentSource(file));
    }

    public String extractTextFromStream(InputStream is, String fileName, String mimeType) {
        return extractTextFromSource(new ByteStreamDocumentSource(is, fileName, mimeType));
    }

    public String extractTextFromBytes(byte[] bytes, String fileName, String mimeType) {
        return extractTextFromSource(new ByteArrayDocumentSource(bytes, fileName, mimeType));
    }


    /**
     * 从 DocumentSource 提取纯文本
     * 支持多 Extractor 降级重试
     *
     * @param source 文档输入源
     * @return 提取的文本（非空去空格），若无法提取则抛出异常
     * @throws IllegalArgumentException 输入源为空
     */
    public String extractTextFromSource(DocumentSource source) {
        if (source == null) {
            throw new IllegalArgumentException("DocumentSource cannot be null");
        }

        try {
            // 获取可用的 Extractor（按优先级排序）
            List<FileExtractor> candidates = registry.findExtractors(source);
            if (candidates.isEmpty()) {
                log.warn("No extractor supports this document: " + safeFileName(source));
                return null;
            }

            // 日志：输出候选 Extractor
            log.info("Trying extractors for {}: {}", safeFileName(source),
                candidates.stream()
                    .map(e -> e.getClass().getSimpleName())
                    .collect(Collectors.joining(", ")));


            for (FileExtractor extractor : candidates) {
                try {
                    log.debug("Trying {} on {}", extractor.getClass().getSimpleName(), safeFileName(source));

                    String text = extractor.extractText(source);
                    if (text != null && !text.trim().isEmpty()) {
                        log.debug("Success with {}: extracted {} chars",
                            extractor.getClass().getSimpleName(), text.length());
                        return text;
                    } else {
                        log.debug("Extractor {} returned null", extractor.getClass().getSimpleName());
                    }
                } catch (Exception e) {
                    log.warn("Extractor {} failed on {}: {}",
                        extractor.getClass().getSimpleName(),
                        safeFileName(source),
                        e.toString());
                }
            }

            log.warn(String.format("All %d extractors failed for: %s", candidates.size(), safeFileName(source)));
            return null;
        } finally {
            source.cleanup();
        }

    }

    private String safeFileName(DocumentSource source) {
        try {
            return source.getFileName() != null ? source.getFileName() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }
}

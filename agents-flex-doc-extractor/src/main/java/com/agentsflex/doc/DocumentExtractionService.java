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
package com.agentsflex.doc;


import com.agentsflex.doc.extractor.DocumentExtractor;
import com.agentsflex.doc.extractor.ExtractorRegistry;
import com.agentsflex.doc.handler.Base64ExtractedImageHandler;
import com.agentsflex.core.document.ExtractedImageHandler;
import com.agentsflex.doc.source.*;

import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

public class DocumentExtractionService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DocumentExtractionService.class);
    private final ExtractorRegistry registry;
    private volatile ExtractedImageHandler extractedImageHandler;

    public DocumentExtractionService() {
        this(new ExtractorRegistry(), new Base64ExtractedImageHandler());
    }

    public DocumentExtractionService(ExtractorRegistry registry) {
        this(registry, new Base64ExtractedImageHandler());
    }

    public DocumentExtractionService(ExtractedImageHandler extractedImageHandler) {
        this(new ExtractorRegistry(), extractedImageHandler);
    }

    public DocumentExtractionService(ExtractorRegistry registry, ExtractedImageHandler extractedImageHandler) {
        if (registry == null) {
            throw new IllegalArgumentException("ExtractorRegistry cannot be null");
        }
        if (extractedImageHandler == null) {
            throw new IllegalArgumentException("ExtractedImageHandler cannot be null");
        }
        this.registry = registry;
        this.extractedImageHandler = extractedImageHandler;
    }

    public ExtractorRegistry getRegistry() {
        return registry;
    }

    public ExtractedImageHandler getExtractedImageHandler() {
        return extractedImageHandler;
    }

    public void setExtractedImageHandler(ExtractedImageHandler extractedImageHandler) {
        if (extractedImageHandler == null) {
            throw new IllegalArgumentException("ExtractedImageHandler cannot be null");
        }
        this.extractedImageHandler = extractedImageHandler;
    }

    public String extractFromUrl(String httpUrl) {
        return extract(new HttpDocumentSource(httpUrl));
    }

    public String extractFromUrl(String httpUrl, String fileName) {
        return extract(new HttpDocumentSource(httpUrl, fileName));
    }

    public String extractFromUrl(String httpUrl, String fileName, String mimeType) {
        return extract(new HttpDocumentSource(httpUrl, fileName, mimeType));
    }

    public String extract(File file) {
        return extract(new FileDocumentSource(file));
    }

    public String extract(InputStream inputStream, String fileName, String mimeType) {
        return extract(new ByteStreamDocumentSource(inputStream, fileName, mimeType));
    }

    public String extract(byte[] bytes, String fileName, String mimeType) {
        return extract(new ByteArrayDocumentSource(bytes, fileName, mimeType));
    }


    /**
     * 从 DocumentSource 提取 Markdown 风格内容，支持多个格式提取器降级重试。
     *
     * @param source 文档输入源
     * @return 提取的内容；没有可用提取器或所有提取器均失败时返回 null
     * @throws IllegalArgumentException 输入源为空
     */
    public String extract(DocumentSource source) {
        if (source == null) {
            throw new IllegalArgumentException("DocumentSource cannot be null");
        }

        ExtractedImageHandler currentExtractedImageHandler = extractedImageHandler;
        try {
            // 获取可用的 Extractor（按优先级排序）
            List<DocumentExtractor> candidates = registry.findExtractors(source);
            if (candidates.isEmpty()) {
                log.warn("No extractor supports this document: " + safeFileName(source));
                return null;
            }

            // 日志：输出候选 Extractor
            log.info("Trying extractors for {}: {}", safeFileName(source),
                candidates.stream()
                    .map(e -> e.getClass().getSimpleName())
                    .collect(Collectors.joining(", ")));


            for (DocumentExtractor extractor : candidates) {
                try {
                    log.debug("Trying {} on {}", extractor.getClass().getSimpleName(), safeFileName(source));

                    String text = extractor.extractText(source, currentExtractedImageHandler);
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

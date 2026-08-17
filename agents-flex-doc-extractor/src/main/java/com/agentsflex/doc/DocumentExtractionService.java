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
import com.agentsflex.core.document.DataUriDocumentImagePublisher;
import com.agentsflex.core.document.DocumentImagePublisher;
import com.agentsflex.doc.source.*;

import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 文档内容提取服务。
 *
 * <p>服务通过 {@link ExtractorRegistry} 选择文档提取器，并通过 {@link DocumentImagePublisher}
 * 统一发布提取过程中发现的图片。默认使用 {@link DataUriDocumentImagePublisher}，将图片直接内联到
 * Markdown。服务实例可以并发复用；替换发布器只影响之后开始的提取调用。</p>
 */
public class DocumentExtractionService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DocumentExtractionService.class);
    private final ExtractorRegistry registry;
    /**
     * 当前图片发布器。使用 volatile 使运行期替换对后续提取请求可见。
     */
    private volatile DocumentImagePublisher documentImagePublisher;

    /**
     * 使用默认提取器注册中心和 Data URI 图片发布器创建服务。
     */
    public DocumentExtractionService() {
        this(new ExtractorRegistry(), new DataUriDocumentImagePublisher());
    }

    /**
     * 使用指定注册中心和默认 Data URI 图片发布器创建服务。
     *
     * @param registry 非空的提取器注册中心
     */
    public DocumentExtractionService(ExtractorRegistry registry) {
        this(registry, new DataUriDocumentImagePublisher());
    }

    /**
     * 使用默认提取器注册中心和指定图片发布器创建服务。
     *
     * @param documentImagePublisher 非空的图片发布器
     */
    public DocumentExtractionService(DocumentImagePublisher documentImagePublisher) {
        this(new ExtractorRegistry(), documentImagePublisher);
    }

    /**
     * 使用指定提取器注册中心和图片发布器创建服务。
     *
     * @param registry 非空的提取器注册中心
     * @param documentImagePublisher 非空的图片发布器
     */
    public DocumentExtractionService(ExtractorRegistry registry, DocumentImagePublisher documentImagePublisher) {
        if (registry == null) {
            throw new IllegalArgumentException("ExtractorRegistry cannot be null");
        }
        if (documentImagePublisher == null) {
            throw new IllegalArgumentException("DocumentImagePublisher cannot be null");
        }
        this.registry = registry;
        this.documentImagePublisher = documentImagePublisher;
    }

    public ExtractorRegistry getRegistry() {
        return registry;
    }

    /**
     * 返回当前图片发布器。
     */
    public DocumentImagePublisher getDocumentImagePublisher() {
        return documentImagePublisher;
    }

    /**
     * 替换图片发布器。正在执行的提取调用继续使用开始时取得的发布器快照。
     *
     * @param documentImagePublisher 非空的图片发布器
     */
    public void setDocumentImagePublisher(DocumentImagePublisher documentImagePublisher) {
        if (documentImagePublisher == null) {
            throw new IllegalArgumentException("DocumentImagePublisher cannot be null");
        }
        this.documentImagePublisher = documentImagePublisher;
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

        DocumentImagePublisher currentDocumentImagePublisher = documentImagePublisher;
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

                    String text = extractor.extractText(source, currentDocumentImagePublisher);
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

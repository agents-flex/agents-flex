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
import com.agentsflex.doc.extractor.StructuredDocumentExtractor;
import com.agentsflex.doc.renderer.DocumentRenderers;
import com.agentsflex.core.document.DataUriDocumentImagePublisher;
import com.agentsflex.core.document.DocumentImagePublisher;
import com.agentsflex.doc.source.*;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
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
    /**
     * 未显式指定输出格式时使用的默认格式。
     *
     * <p>正文保持 Markdown，表格使用 HTML，以便默认保留合并单元格。</p>
     */
    private static final DocumentOutputFormat DEFAULT_OUTPUT_FORMAT =
        DocumentOutputFormat.MARKDOWN_WITH_HTML_TABLES;

    /**
     * 当前类使用的日志记录器。
     */
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DocumentExtractionService.class);

    /**
     * 用于按文档类型查找候选解析器的注册中心。
     */
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
     * @param registry               非空的提取器注册中心
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

    /**
     * 获取当前服务使用的解析器注册中心。
     *
     * @return 解析器注册中心
     */
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

    /**
     * 从 URL 提取 Markdown 正文与 HTML 表格融合内容。
     *
     * @param url HTTP、HTTPS 或 file URL
     * @return 提取结果
     */
    public String extractFromUrl(String url) {
        return extractFromUrl(url, null, null);
    }

    /**
     * 从 URL 提取 Markdown 正文与 HTML 表格融合内容，并显式提供原始文件名。
     *
     * @param url      HTTP、HTTPS 或 file URL
     * @param fileName 原始文件名
     * @return 提取结果
     */
    public String extractFromUrl(String url, String fileName) {
        return extractFromUrl(url, fileName, null);
    }

    /**
     * 从 HTTP(S) 或本地 file URL 提取正文。对象键没有扩展名时可传入原始文件名和 MIME 类型。
     * 调用方应在传入 URL 前完成访问授权，尤其不能将不受信任的 file URL 直接交给此方法。
     *
     * @param url      HTTP、HTTPS 或 file URL
     * @param fileName 原始文件名
     * @param mimeType 文档 MIME 类型
     * @return Markdown 正文与 HTML 表格融合结果
     */
    public String extractFromUrl(String url, String fileName, String mimeType) {
        return extractFromUrl(url, fileName, mimeType, DEFAULT_OUTPUT_FORMAT);
    }

    /**
     * 从 URL 提取指定格式的文档内容。
     *
     * <p>对于 HTTP(S) 地址会直接发起 GET 请求；对于 {@code file:} 地址会读取本地文件。
     * 当对象存储键没有扩展名时，应通过 {@code fileName} 和 {@code mimeType} 提供真实元数据。</p>
     *
     * @param url          HTTP、HTTPS 或 file URL
     * @param fileName     原始文件名，可为 {@code null}
     * @param mimeType     文档 MIME 类型，可为 {@code null}
     * @param outputFormat 输出格式，传入 {@code null} 时使用 Markdown 正文与 HTML 表格融合格式
     * @return 提取结果；没有可用解析器或解析失败时返回 {@code null}
     */
    public String extractFromUrl(String url, String fileName, String mimeType,
                                 DocumentOutputFormat outputFormat) {
        URI uri = URI.create(url);
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            return extract(new FileDocumentSource(new File(uri), fileName, mimeType), outputFormat);
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Document URL must use http, https or file");
        }
        return extract(new HttpDocumentSource(url, fileName, mimeType), outputFormat);
    }

    /**
     * 从本地文件提取 Markdown 正文与 HTML 表格融合内容。
     *
     * @param file 本地文档文件
     * @return 提取结果
     */
    public String extract(File file) {
        return extract(new FileDocumentSource(file));
    }

    /**
     * 从本地文件提取指定格式的内容。
     *
     * @param file         本地文档文件
     * @param outputFormat 输出格式
     * @return 提取结果；没有可用解析器或解析失败时返回 {@code null}
     */
    public String extract(File file, DocumentOutputFormat outputFormat) {
        return extract(new FileDocumentSource(file), outputFormat);
    }

    /**
     * 从本地文件提取完整 HTML 片段。
     *
     * @param file 本地文档文件
     * @return HTML 片段；没有可用解析器或解析失败时返回 {@code null}
     */
    public String extractHtml(File file) {
        return extract(file, DocumentOutputFormat.HTML);
    }

    /**
     * 从本地文件提取 Markdown 正文与 HTML 表格融合内容。
     *
     * @param file 本地文档文件
     * @return 融合格式文本；没有可用解析器或解析失败时返回 {@code null}
     */
    public String extractHybridMarkdown(File file) {
        return extract(file, DocumentOutputFormat.MARKDOWN_WITH_HTML_TABLES);
    }

    /**
     * 从输入流提取 Markdown 正文与 HTML 表格融合内容。
     *
     * @param inputStream 文档输入流
     * @param fileName    原始文件名
     * @param mimeType    文档 MIME 类型
     * @return 提取结果
     */
    public String extract(InputStream inputStream, String fileName, String mimeType) {
        return extract(new ByteStreamDocumentSource(inputStream, fileName, mimeType));
    }

    /**
     * 从输入流提取指定格式的内容。
     *
     * <p>输入流由对应的 {@link ByteStreamDocumentSource} 管理，提取完成后会随输入源清理。</p>
     *
     * @param inputStream  文档输入流
     * @param fileName     原始文件名
     * @param mimeType     文档 MIME 类型
     * @param outputFormat 输出格式
     * @return 提取结果；没有可用解析器或解析失败时返回 {@code null}
     */
    public String extract(InputStream inputStream, String fileName, String mimeType,
                          DocumentOutputFormat outputFormat) {
        return extract(new ByteStreamDocumentSource(inputStream, fileName, mimeType), outputFormat);
    }

    /**
     * 从字节数组提取 Markdown 正文与 HTML 表格融合内容。
     *
     * @param bytes    文档二进制内容
     * @param fileName 原始文件名
     * @param mimeType 文档 MIME 类型
     * @return 提取结果
     */
    public String extract(byte[] bytes, String fileName, String mimeType) {
        return extract(new ByteArrayDocumentSource(bytes, fileName, mimeType));
    }

    /**
     * 从字节数组提取指定格式的内容。
     *
     * @param bytes        文档二进制内容
     * @param fileName     原始文件名
     * @param mimeType     文档 MIME 类型
     * @param outputFormat 输出格式
     * @return 提取结果；没有可用解析器或解析失败时返回 {@code null}
     */
    public String extract(byte[] bytes, String fileName, String mimeType,
                          DocumentOutputFormat outputFormat) {
        return extract(new ByteArrayDocumentSource(bytes, fileName, mimeType), outputFormat);
    }

    /**
     * 从字节数组提取完整 HTML 片段。
     *
     * @param bytes    文档二进制内容
     * @param fileName 原始文件名
     * @param mimeType 文档 MIME 类型
     * @return HTML 片段；没有可用解析器或解析失败时返回 {@code null}
     */
    public String extractHtml(byte[] bytes, String fileName, String mimeType) {
        return extract(bytes, fileName, mimeType, DocumentOutputFormat.HTML);
    }

    /**
     * 从字节数组提取 Markdown 正文与 HTML 表格融合内容。
     *
     * @param bytes    文档二进制内容
     * @param fileName 原始文件名
     * @param mimeType 文档 MIME 类型
     * @return 融合格式文本；没有可用解析器或解析失败时返回 {@code null}
     */
    public String extractHybridMarkdown(byte[] bytes, String fileName, String mimeType) {
        return extract(bytes, fileName, mimeType, DocumentOutputFormat.MARKDOWN_WITH_HTML_TABLES);
    }


    /**
     * 从 DocumentSource 提取 Markdown 正文与 HTML 表格融合内容，支持多个格式提取器降级重试。
     *
     * @param source 文档输入源
     * @return 提取的内容；没有可用提取器或所有提取器均失败时返回 null
     * @throws IllegalArgumentException 输入源为空
     */
    public String extract(DocumentSource source) {
        return extract(source, DEFAULT_OUTPUT_FORMAT);
    }

    /**
     * 按指定格式提取文档。支持结构化输出的解析器会先产生中间模型再渲染；
     * 旧解析器在非 Markdown 模式下保持原文本作为兼容降级。
     *
     * <p>每次调用会获取当前图片发布器快照，并按注册优先级依次尝试支持该文档的解析器。
     * 无论成功或失败，最终都会调用输入源的清理方法。</p>
     *
     * @param source       文档输入源
     * @param outputFormat 输出格式，传入 {@code null} 时使用 Markdown 正文与 HTML 表格融合格式
     * @return 第一个成功解析的非空结果；全部失败时返回 {@code null}
     * @throws IllegalArgumentException 输入源为空
     */
    public String extract(DocumentSource source, DocumentOutputFormat outputFormat) {
        if (source == null) {
            throw new IllegalArgumentException("DocumentSource cannot be null");
        }
        DocumentOutputFormat effectiveFormat = outputFormat == null
            ? DEFAULT_OUTPUT_FORMAT : outputFormat;

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

                    String text;
                    if (extractor instanceof StructuredDocumentExtractor) {
                        StructuredDocumentExtractor structured = (StructuredDocumentExtractor) extractor;
                        text = DocumentRenderers.forFormat(effectiveFormat).render(
                            structured.extractDocument(source, currentDocumentImagePublisher));
                    } else {
                        text = extractor.extractText(source, currentDocumentImagePublisher);
                    }
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

    /**
     * 安全读取用于日志展示的文件名。
     *
     * @param source 文档输入源
     * @return 文件名不可用时返回 {@code unknown}
     */
    private String safeFileName(DocumentSource source) {
        try {
            return source.getFileName() != null ? source.getFileName() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }
}

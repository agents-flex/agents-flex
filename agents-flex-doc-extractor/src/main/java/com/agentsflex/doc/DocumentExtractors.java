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


import com.agentsflex.core.document.DocumentImagePublisher;
import com.agentsflex.doc.source.ByteArrayDocumentSource;
import com.agentsflex.doc.source.ByteStreamDocumentSource;
import com.agentsflex.doc.source.FileDocumentSource;

import java.io.File;
import java.io.InputStream;

/**
 * 进程级默认 {@link DocumentExtractionService} 的静态便捷入口。
 *
 * <p>简单应用可以直接使用本类；需要多租户、请求级图片发布器或自定义解析器注册表时，
 * 应创建独立的 {@link DocumentExtractionService} 实例。</p>
 */
public class DocumentExtractors {
    /**
     * 当前进程级默认提取服务，可通过 {@link #setDefault(DocumentExtractionService)} 替换。
     */
    private static volatile DocumentExtractionService defaultService = new DocumentExtractionService();

    /**
     * 工具类不允许实例化。
     */
    private DocumentExtractors() {
    }

    /**
     * 替换进程级默认提取服务。
     *
     * @param service 新的默认服务，不允许为 {@code null}
     */
    public static void setDefault(DocumentExtractionService service) {
        if (service == null) {
            throw new IllegalArgumentException("DocumentExtractionService cannot be null");
        }
        DocumentExtractors.defaultService = service;
    }

    /**
     * 设置进程级默认文档图片发布器。
     *
     * <p>该配置作用于静态入口持有的默认 {@link DocumentExtractionService}，通常应在应用启动阶段设置一次。
     * 多租户或请求级图片策略应创建独立的服务实例，避免并发请求互相覆盖全局配置。</p>
     *
     * @param documentImagePublisher 非空的图片发布器
     */
    public static void setDocumentImagePublisher(DocumentImagePublisher documentImagePublisher) {
        defaultService.setDocumentImagePublisher(documentImagePublisher);
    }

    /**
     * 从 URL 提取 Markdown 正文与 HTML 表格融合内容。
     *
     * @param url HTTP、HTTPS 或 file URL
     * @return 提取结果
     */
    public static String extractFromUrl(String url) {
        return defaultService.extractFromUrl(url);
    }

    /**
     * 从 URL 提取 Markdown 正文与 HTML 表格融合内容，并显式提供原始文件名。
     *
     * @param url      HTTP、HTTPS 或 file URL
     * @param fileName 原始文件名
     * @return 提取结果
     */
    public static String extractFromUrl(String url, String fileName) {
        return defaultService.extractFromUrl(url, fileName);
    }

    /**
     * 从 URL 提取 Markdown 正文与 HTML 表格融合内容，并显式提供原始文件元数据。
     *
     * @param url      HTTP、HTTPS 或 file URL
     * @param fileName 原始文件名
     * @param mimeType 文档 MIME 类型
     * @return 提取结果
     */
    public static String extractFromUrl(String url, String fileName, String mimeType) {
        return defaultService.extractFromUrl(url, fileName, mimeType);
    }

    /**
     * 从 URL 提取指定格式的文档内容。
     *
     * @param url          HTTP、HTTPS 或 file URL
     * @param fileName     原始文件名
     * @param mimeType     文档 MIME 类型
     * @param outputFormat 输出格式
     * @return 提取结果
     */
    public static String extractFromUrl(String url, String fileName, String mimeType,
                                        DocumentOutputFormat outputFormat) {
        return defaultService.extractFromUrl(url, fileName, mimeType, outputFormat);
    }

    /**
     * 从本地文件提取 Markdown 正文与 HTML 表格融合内容。
     *
     * @param file 本地文档文件
     * @return 提取结果
     */
    public static String extract(File file) {
        return defaultService.extract(new FileDocumentSource(file));
    }

    /**
     * 从本地文件提取指定格式的内容。
     *
     * @param file         本地文档文件
     * @param outputFormat 输出格式
     * @return 提取结果
     */
    public static String extract(File file, DocumentOutputFormat outputFormat) {
        return defaultService.extract(new FileDocumentSource(file), outputFormat);
    }

    /**
     * 从本地文件提取完整 HTML 片段。
     *
     * @param file 本地文档文件
     * @return HTML 片段
     */
    public static String extractHtml(File file) {
        return defaultService.extractHtml(file);
    }

    /**
     * 从本地文件提取 Markdown 正文与 HTML 表格融合内容。
     *
     * @param file 本地文档文件
     * @return 融合格式文本
     */
    public static String extractHybridMarkdown(File file) {
        return defaultService.extractHybridMarkdown(file);
    }

    /**
     * 从输入流提取 Markdown 正文与 HTML 表格融合内容。
     *
     * @param inputStream 文档输入流
     * @param fileName    原始文件名
     * @param mimeType    文档 MIME 类型
     * @return 提取结果
     */
    public static String extract(InputStream inputStream, String fileName, String mimeType) {
        return defaultService.extract(new ByteStreamDocumentSource(inputStream, fileName, mimeType));
    }

    /**
     * 从输入流提取指定格式的内容。
     *
     * @param inputStream  文档输入流
     * @param fileName     原始文件名
     * @param mimeType     文档 MIME 类型
     * @param outputFormat 输出格式
     * @return 提取结果
     */
    public static String extract(InputStream inputStream, String fileName, String mimeType,
                                 DocumentOutputFormat outputFormat) {
        return defaultService.extract(new ByteStreamDocumentSource(inputStream, fileName, mimeType), outputFormat);
    }

    /**
     * 从字节数组提取 Markdown 正文与 HTML 表格融合内容。
     *
     * @param bytes    文档二进制内容
     * @param fileName 原始文件名
     * @param mimeType 文档 MIME 类型
     * @return 提取结果
     */
    public static String extract(byte[] bytes, String fileName, String mimeType) {
        return defaultService.extract(new ByteArrayDocumentSource(bytes, fileName, mimeType));
    }

    /**
     * 从字节数组提取指定格式的内容。
     *
     * @param bytes        文档二进制内容
     * @param fileName     原始文件名
     * @param mimeType     文档 MIME 类型
     * @param outputFormat 输出格式
     * @return 提取结果
     */
    public static String extract(byte[] bytes, String fileName, String mimeType,
                                 DocumentOutputFormat outputFormat) {
        return defaultService.extract(new ByteArrayDocumentSource(bytes, fileName, mimeType), outputFormat);
    }

    /**
     * 从字节数组提取完整 HTML 片段。
     *
     * @param bytes    文档二进制内容
     * @param fileName 原始文件名
     * @param mimeType 文档 MIME 类型
     * @return HTML 片段
     */
    public static String extractHtml(byte[] bytes, String fileName, String mimeType) {
        return defaultService.extractHtml(bytes, fileName, mimeType);
    }

    /**
     * 从字节数组提取 Markdown 正文与 HTML 表格融合内容。
     *
     * @param bytes    文档二进制内容
     * @param fileName 原始文件名
     * @param mimeType 文档 MIME 类型
     * @return 融合格式文本
     */
    public static String extractHybridMarkdown(byte[] bytes, String fileName, String mimeType) {
        return defaultService.extractHybridMarkdown(bytes, fileName, mimeType);
    }


}

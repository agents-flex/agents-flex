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
package com.agentsflex.core.file2text.source;


import com.agentsflex.core.file2text.util.IOUtils;

import java.io.*;
import java.nio.file.Files;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * 将输入流保存到临时文件的 DocumentSource
 * 适用于大文件，避免内存溢出
 * 线程安全，文件名唯一，支持自动清理
 */
public class TemporaryFileStreamDocumentSource implements DocumentSource {
    private static final Logger log = Logger.getLogger(TemporaryFileStreamDocumentSource.class.getName());
    private static final long DEFAULT_MAX_SIZE = 100 * 1024 * 1024; // 100MB

    private final File tempFile;
    private final String fileName;
    private final String mimeType;

    /**
     * 创建临时文件源（默认最大 100MB）
     */
    public TemporaryFileStreamDocumentSource(InputStream inputStream, String fileName, String mimeType) throws IOException {
        this(inputStream, fileName, mimeType, DEFAULT_MAX_SIZE);
    }

    /**
     * 创建临时文件源（可指定最大大小）
     *
     * @param inputStream 输入流
     * @param fileName    建议文件名（用于日志和扩展名推断）
     * @param mimeType    MIME 类型（可选）
     * @param maxSize     最大允许大小（字节）
     * @throws IOException 文件过大或 I/O 错误
     */
    public TemporaryFileStreamDocumentSource(
            InputStream inputStream,
            String fileName,
            String mimeType,
            long maxSize) throws IOException {

        Objects.requireNonNull(inputStream, "InputStream cannot be null");

        this.fileName = sanitizeFileName(fileName);
        this.mimeType = mimeType;

        // 推断后缀（用于调试）
        String suffix = inferSuffix(this.fileName);

        // 创建唯一临时文件
        this.tempFile = File.createTempFile("doc-", suffix);
        this.tempFile.deleteOnExit(); // JVM 退出时清理

        log.info("Creating temp file for " + this.fileName + ": " + tempFile.getAbsolutePath());

        // 复制流（带大小限制）
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            IOUtils.copyStream(inputStream, fos, maxSize);
        } catch (IOException e) {
            // 清理失败的临时文件
            boolean deleted = tempFile.delete();
            log.warning("Failed to write temp file, deleted: " + deleted);
            throw e;
        }

        log.fine("Temp file created: " + tempFile.length() + " bytes");
    }

    @Override
    public String getFileName() {
        return fileName;
    }

    @Override
    public String getMimeType() {
        return mimeType;
    }


    @Override
    public InputStream openStream() throws IOException {
        if (!tempFile.exists()) {
            throw new FileNotFoundException("Temp file not found: " + tempFile.getAbsolutePath());
        }
        return Files.newInputStream(tempFile.toPath());
    }

    @Override
    public void cleanup() {
        if (tempFile.exists()) {
            boolean deleted = tempFile.delete();
            if (!deleted) {
                log.warning("Failed to delete temp file: " + tempFile.getAbsolutePath());
            } else {
                log.fine("Cleaned up temp file: " + tempFile.getAbsolutePath());
            }
        }
    }

    // ========================
    // 工具方法
    // ========================

    /**
     * 推断文件后缀（用于临时文件命名，便于调试）
     */
    private String inferSuffix(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return ".tmp";
        }
        int lastDot = fileName.lastIndexOf('.');
        String ext = fileName.substring(lastDot); // 包含 .
        if (ext.length() > 1 && ext.length() <= 10 && ext.matches("\\.[a-zA-Z0-9]{1,10}")) {
            return ext;
        }
        return ".tmp";
    }

    /**
     * 清理文件名中的非法字符
     */
    private String sanitizeFileName(String fileName) {
        if (fileName == null) return "unknown";
        return fileName
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\.\\.", "_")
                .replaceAll("^\\s+|\\s+$", "")
                .isEmpty() ? "file" : fileName;
    }
}

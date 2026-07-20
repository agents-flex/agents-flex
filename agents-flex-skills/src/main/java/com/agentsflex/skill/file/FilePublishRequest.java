/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.skill.file;

import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次文件发布请求。
 *
 * <p>请求对象不可变，其中的 InputStream 只能消费一次。{@code sourcePath} 和
 * {@code runtimeName} 用于审计与发布策略判断，不应直接作为对象存储 Key 或公开 URL。</p>
 */
public class FilePublishRequest {

    private final InputStream inputStream;
    private final String fileName;
    private final String contentType;
    private final long contentLength;
    private final String sourcePath;
    private final String runtimeName;
    private final String checksum;
    private final Map<String, Object> metadata;

    private FilePublishRequest(Builder builder) {
        if (builder.inputStream == null) {
            throw new IllegalArgumentException("inputStream must not be null");
        }
        if (noText(builder.fileName)) {
            throw new IllegalArgumentException("fileName must not be empty");
        }
        if (builder.contentLength < -1) {
            throw new IllegalArgumentException("contentLength must be -1 or greater");
        }
        this.inputStream = builder.inputStream;
        this.fileName = builder.fileName;
        this.contentType = builder.contentType;
        this.contentLength = builder.contentLength;
        this.sourcePath = builder.sourcePath;
        this.runtimeName = builder.runtimeName;
        this.checksum = builder.checksum;
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(builder.metadata));
    }

    /** @return 新的请求构建器 */
    public static Builder builder() {
        return new Builder();
    }

    /** @return 只能同步消费一次、由调用方负责关闭的文件输入流 */
    public InputStream getInputStream() {
        return inputStream;
    }

    /** @return 对外展示的文件名 */
    public String getFileName() {
        return fileName;
    }

    /** @return MIME 类型；无法推断时可能为 {@code null} */
    public String getContentType() {
        return contentType;
    }

    /** @return 文件字节数；未知时为 {@code -1} */
    public long getContentLength() {
        return contentLength;
    }

    /** @return Runtime 内原始文件路径 */
    public String getSourcePath() {
        return sourcePath;
    }

    /** @return 来源 Runtime 名称 */
    public String getRuntimeName() {
        return runtimeName;
    }

    /** @return 可选内容校验值，例如 SHA-256 */
    public String getChecksum() {
        return checksum;
    }

    /** @return 不可变扩展元数据 */
    public Map<String, Object> getMetadata() {
        return metadata;
    }

    private static boolean noText(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** 文件发布请求构建器。 */
    public static class Builder {

        private InputStream inputStream;
        private String fileName;
        private String contentType;
        private long contentLength = -1;
        private String sourcePath;
        private String runtimeName;
        private String checksum;
        private Map<String, Object> metadata = Collections.emptyMap();

        public Builder inputStream(InputStream inputStream) {
            this.inputStream = inputStream;
            return this;
        }

        public Builder fileName(String fileName) {
            this.fileName = fileName;
            return this;
        }

        public Builder contentType(String contentType) {
            this.contentType = contentType;
            return this;
        }

        public Builder contentLength(long contentLength) {
            this.contentLength = contentLength;
            return this;
        }

        public Builder sourcePath(String sourcePath) {
            this.sourcePath = sourcePath;
            return this;
        }

        public Builder runtimeName(String runtimeName) {
            this.runtimeName = runtimeName;
            return this;
        }

        public Builder checksum(String checksum) {
            this.checksum = checksum;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata == null ? Collections.<String, Object>emptyMap() : metadata;
            return this;
        }

        public FilePublishRequest build() {
            return new FilePublishRequest(this);
        }
    }
}

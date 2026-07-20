/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.skill.file;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文件发布完成后的标准结果。
 *
 * <p>{@code url} 是模型最终交付给用户的地址，可以是公开 URL，也可以是有有效期的
 * 签名 URL。{@code storageKey} 仅供业务审计或后续删除使用，不应替代 URL。</p>
 */
public class PublishedFile {

    private final String url;
    private final String fileName;
    private final String contentType;
    private final long contentLength;
    private final Long expiresAt;
    private final String storageKey;
    private final Map<String, Object> metadata;

    private PublishedFile(Builder builder) {
        if (builder.url == null || builder.url.trim().isEmpty()) {
            throw new IllegalArgumentException("url must not be empty");
        }
        if (builder.contentLength < -1) {
            throw new IllegalArgumentException("contentLength must be -1 or greater");
        }
        this.url = builder.url;
        this.fileName = builder.fileName;
        this.contentType = builder.contentType;
        this.contentLength = builder.contentLength;
        this.expiresAt = builder.expiresAt;
        this.storageKey = builder.storageKey;
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(builder.metadata));
    }

    /** @return 新的发布结果构建器 */
    public static Builder builder() {
        return new Builder();
    }

    public String getUrl() {
        return url;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public long getContentLength() {
        return contentLength;
    }

    /** @return URL 失效时间的 Unix 毫秒值；永久地址或未知时为 {@code null} */
    public Long getExpiresAt() {
        return expiresAt;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /** 已发布文件构建器。 */
    public static class Builder {

        private String url;
        private String fileName;
        private String contentType;
        private long contentLength = -1;
        private Long expiresAt;
        private String storageKey;
        private Map<String, Object> metadata = Collections.emptyMap();

        public Builder url(String url) {
            this.url = url;
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

        public Builder expiresAt(Long expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        public Builder storageKey(String storageKey) {
            this.storageKey = storageKey;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata == null ? Collections.<String, Object>emptyMap() : metadata;
            return this;
        }

        public PublishedFile build() {
            return new PublishedFile(this);
        }
    }
}

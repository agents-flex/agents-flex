/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.skill.artifact.aliyun.oss;

import com.agentsflex.skill.artifact.objectstorage.ObjectStorageSkillArtifactStore;
import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.OSSClientBuilder;
import com.aliyun.sdk.service.oss2.credentials.CredentialsProvider;
import com.aliyun.sdk.service.oss2.credentials.EnvironmentVariableCredentialsProvider;
import com.aliyun.sdk.service.oss2.credentials.StaticCredentialsProvider;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 使用阿里云 OSS Java SDK V2 的 Skill Artifact Store。
 *
 * <p>对象存储无关的安装、摘要校验和节点缓存逻辑由
 * {@link ObjectStorageSkillArtifactStore} 提供，本类只负责 OSSClient 配置和适配。</p>
 */
public class AliyunOssSkillArtifactStore extends ObjectStorageSkillArtifactStore {

    /** 使用调用方管理生命周期的 OSSClient。 */
    public AliyunOssSkillArtifactStore(OSSClient client, String bucket, String keyPrefix, Path cacheDirectory) {
        this(client, false, bucket, keyPrefix, cacheDirectory,
            ObjectStorageSkillArtifactStore.DEFAULT_MAX_PACKAGE_SIZE);
    }

    /** 使用可选自主管理生命周期的 OSSClient。 */
    public AliyunOssSkillArtifactStore(OSSClient client, boolean closeClient, String bucket, String keyPrefix,
                                 Path cacheDirectory, long maxPackageSize) {
        super(new AliyunOssObjectStorageOperations(requireClient(client), closeClient),
            bucket, keyPrefix, cacheDirectory, maxPackageSize);
    }

    public static Builder builder() {
        return new Builder();
    }

    private static OSSClient requireClient(OSSClient client) {
        if (client == null) {
            throw new IllegalArgumentException("client must not be null");
        }
        return client;
    }

    /** OSS Skill Artifact Store 构建器。 */
    public static class Builder {

        private String region;
        private String endpoint;
        private String bucket;
        private String keyPrefix = "agents-flex/skills";
        private Path cacheDirectory = Paths.get(System.getProperty("java.io.tmpdir"),
            "agents-flex", "skills-artifact-cache", "oss");
        private CredentialsProvider credentialsProvider;
        private String accessKeyId;
        private String accessKeySecret;
        private String securityToken;
        private long maxPackageSize = ObjectStorageSkillArtifactStore.DEFAULT_MAX_PACKAGE_SIZE;

        public Builder region(String region) {
            this.region = region;
            return this;
        }

        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public Builder bucket(String bucket) {
            this.bucket = bucket;
            return this;
        }

        public Builder keyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
            return this;
        }

        public Builder cacheDirectory(Path cacheDirectory) {
            this.cacheDirectory = cacheDirectory;
            return this;
        }

        public Builder credentialsProvider(CredentialsProvider credentialsProvider) {
            this.credentialsProvider = credentialsProvider;
            return this;
        }

        /** 配置静态 AccessKey ID。不要记录或输出该值。 */
        public Builder accessKeyId(String accessKeyId) {
            this.accessKeyId = accessKeyId;
            return this;
        }

        /** 配置静态 AccessKey Secret。不要记录或输出该值。 */
        public Builder accessKeySecret(String accessKeySecret) {
            this.accessKeySecret = accessKeySecret;
            return this;
        }

        /** 配置 STS Security Token。使用长期 AccessKey 时不需要设置。 */
        public Builder securityToken(String securityToken) {
            this.securityToken = securityToken;
            return this;
        }

        /** 配置静态长期 AccessKey。 */
        public Builder credentials(String accessKeyId, String accessKeySecret) {
            this.accessKeyId = accessKeyId;
            this.accessKeySecret = accessKeySecret;
            this.securityToken = null;
            return this;
        }

        /** 配置 STS 临时访问凭证。 */
        public Builder credentials(String accessKeyId, String accessKeySecret, String securityToken) {
            this.accessKeyId = accessKeyId;
            this.accessKeySecret = accessKeySecret;
            this.securityToken = securityToken;
            return this;
        }

        public Builder maxPackageSize(long maxPackageSize) {
            this.maxPackageSize = maxPackageSize;
            return this;
        }

        public AliyunOssSkillArtifactStore build() {
            validate();
            CredentialsProvider provider = resolveCredentialsProvider();
            OSSClientBuilder clientBuilder = OSSClient.newBuilder()
                .credentialsProvider(provider)
                .region(region.trim());
            if (endpoint != null && !endpoint.trim().isEmpty()) {
                clientBuilder.endpoint(endpoint.trim());
            }
            OSSClient client = clientBuilder.build();
            try {
                return new AliyunOssSkillArtifactStore(client, true, bucket, keyPrefix,
                    cacheDirectory, maxPackageSize);
            } catch (RuntimeException e) {
                try {
                    client.close();
                } catch (Exception closeError) {
                    e.addSuppressed(closeError);
                }
                throw e;
            }
        }

        private void validate() {
            if (region == null || region.trim().isEmpty()) {
                throw new IllegalArgumentException("region must not be blank");
            }
            if (bucket == null || bucket.trim().isEmpty()) {
                throw new IllegalArgumentException("bucket must not be blank");
            }
            if (cacheDirectory == null) {
                throw new IllegalArgumentException("cacheDirectory must not be null");
            }
            if (maxPackageSize <= 0) {
                throw new IllegalArgumentException("maxPackageSize must be greater than zero");
            }
        }

        private CredentialsProvider resolveCredentialsProvider() {
            boolean hasAccessKeyId = !isBlank(accessKeyId);
            boolean hasAccessKeySecret = !isBlank(accessKeySecret);
            boolean hasSecurityToken = !isBlank(securityToken);
            boolean hasStaticCredentials = hasAccessKeyId || hasAccessKeySecret || hasSecurityToken;

            if (credentialsProvider != null && hasStaticCredentials) {
                throw new IllegalArgumentException(
                    "credentialsProvider and static AccessKey credentials must not be configured together");
            }
            if (credentialsProvider != null) {
                return credentialsProvider;
            }
            if (!hasStaticCredentials) {
                return new EnvironmentVariableCredentialsProvider();
            }
            if (!hasAccessKeyId || !hasAccessKeySecret) {
                throw new IllegalArgumentException(
                    "accessKeyId and accessKeySecret must be configured together");
            }
            if (hasSecurityToken) {
                return new StaticCredentialsProvider(accessKeyId, accessKeySecret, securityToken);
            }
            return new StaticCredentialsProvider(accessKeyId, accessKeySecret);
        }

        private boolean isBlank(String value) {
            return value == null || value.trim().isEmpty();
        }
    }
}

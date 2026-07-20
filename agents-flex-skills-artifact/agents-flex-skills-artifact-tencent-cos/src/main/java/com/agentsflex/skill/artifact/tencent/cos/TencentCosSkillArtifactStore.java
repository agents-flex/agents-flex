/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.skill.artifact.tencent.cos;

import com.agentsflex.skill.artifact.objectstorage.ObjectStorageSkillArtifactStore;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.BasicSessionCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.auth.COSCredentialsProvider;
import com.qcloud.cos.region.Region;

import java.nio.file.Path;
import java.nio.file.Paths;

/** 使用腾讯云 COS Java SDK 的 Skill Artifact Store。 */
public class TencentCosSkillArtifactStore extends ObjectStorageSkillArtifactStore {

    /** 使用调用方管理生命周期的 COSClient。 */
    public TencentCosSkillArtifactStore(COSClient client, String bucket, String keyPrefix, Path cacheDirectory) {
        this(client, false, bucket, keyPrefix, cacheDirectory,
            ObjectStorageSkillArtifactStore.DEFAULT_MAX_PACKAGE_SIZE);
    }

    /** 使用可选自主管理生命周期的 COSClient。 */
    public TencentCosSkillArtifactStore(COSClient client, boolean closeClient, String bucket, String keyPrefix,
                                 Path cacheDirectory, long maxPackageSize) {
        super(new TencentCosObjectStorageOperations(requireClient(client), closeClient),
            bucket, keyPrefix, cacheDirectory, maxPackageSize);
    }

    public static Builder builder() {
        return new Builder();
    }

    private static COSClient requireClient(COSClient client) {
        if (client == null) {
            throw new IllegalArgumentException("client must not be null");
        }
        return client;
    }

    /** COS Skill Artifact Store 构建器。 */
    public static class Builder {

        private String region;
        private String bucket;
        private String keyPrefix = "agents-flex/skills";
        private Path cacheDirectory = Paths.get(System.getProperty("java.io.tmpdir"),
            "agents-flex", "skills-artifact-cache", "cos");
        private COSCredentialsProvider credentialsProvider;
        private String accessKeyId;
        private String accessKeySecret;
        private String securityToken;
        private long maxPackageSize = ObjectStorageSkillArtifactStore.DEFAULT_MAX_PACKAGE_SIZE;

        public Builder region(String region) {
            this.region = region;
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

        public Builder credentialsProvider(COSCredentialsProvider credentialsProvider) {
            this.credentialsProvider = credentialsProvider;
            return this;
        }

        public Builder accessKeyId(String accessKeyId) {
            this.accessKeyId = accessKeyId;
            return this;
        }

        public Builder accessKeySecret(String accessKeySecret) {
            this.accessKeySecret = accessKeySecret;
            return this;
        }

        public Builder securityToken(String securityToken) {
            this.securityToken = securityToken;
            return this;
        }

        public Builder credentials(String accessKeyId, String accessKeySecret) {
            this.accessKeyId = accessKeyId;
            this.accessKeySecret = accessKeySecret;
            this.securityToken = null;
            return this;
        }

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

        public TencentCosSkillArtifactStore build() {
            validate();
            COSCredentialsProvider provider = resolveCredentialsProvider();
            COSClient client = new COSClient(provider, new ClientConfig(new Region(region.trim())));
            try {
                return new TencentCosSkillArtifactStore(client, true, bucket, keyPrefix,
                    cacheDirectory, maxPackageSize);
            } catch (RuntimeException e) {
                client.shutdown();
                throw e;
            }
        }

        private void validate() {
            if (isBlank(region)) {
                throw new IllegalArgumentException("region must not be blank");
            }
            if (isBlank(bucket)) {
                throw new IllegalArgumentException("bucket must not be blank");
            }
            if (cacheDirectory == null) {
                throw new IllegalArgumentException("cacheDirectory must not be null");
            }
            if (maxPackageSize <= 0) {
                throw new IllegalArgumentException("maxPackageSize must be greater than zero");
            }
        }

        private COSCredentialsProvider resolveCredentialsProvider() {
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
            if (!hasAccessKeyId || !hasAccessKeySecret) {
                throw new IllegalArgumentException(
                    "accessKeyId and accessKeySecret must be configured together");
            }
            final COSCredentials credentials = hasSecurityToken
                ? new BasicSessionCredentials(accessKeyId, accessKeySecret, securityToken)
                : new BasicCOSCredentials(accessKeyId, accessKeySecret);
            return new COSCredentialsProvider() {
                @Override
                public COSCredentials getCredentials() {
                    return credentials;
                }

                @Override
                public void refresh() {
                    // Static credentials do not need refreshing.
                }
            };
        }

        private boolean isBlank(String value) {
            return value == null || value.trim().isEmpty();
        }
    }
}

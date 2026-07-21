/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.skill.artifact.s3;

import com.agentsflex.skill.artifact.objectstorage.ObjectStorageSkillArtifactStore;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 使用 AWS SDK for Java V2 访问 AWS S3 或 S3-compatible 存储的 Skill Artifact Store。 */
public class S3SkillArtifactStore extends ObjectStorageSkillArtifactStore {

    /** 使用调用方管理生命周期的 S3Client。 */
    public S3SkillArtifactStore(S3Client client, String bucket, String keyPrefix, Path cacheDirectory) {
        this(client, false, bucket, keyPrefix, cacheDirectory,
            ObjectStorageSkillArtifactStore.DEFAULT_MAX_PACKAGE_SIZE);
    }

    /** 使用可选自主管理生命周期的 S3Client。 */
    public S3SkillArtifactStore(S3Client client, boolean closeClient, String bucket, String keyPrefix,
                                Path cacheDirectory, long maxPackageSize) {
        super(new S3ObjectStorageOperations(requireClient(client), closeClient),
            bucket, keyPrefix, cacheDirectory, maxPackageSize);
    }

    public static Builder builder() {
        return new Builder();
    }

    private static S3Client requireClient(S3Client client) {
        if (client == null) {
            throw new IllegalArgumentException("client must not be null");
        }
        return client;
    }

    /** S3 Skill Artifact Store 构建器。 */
    public static class Builder {

        private String region;
        private URI endpoint;
        private boolean forcePathStyle;
        private String bucket;
        private String keyPrefix = "agents-flex/skills";
        private Path cacheDirectory = Paths.get(System.getProperty("java.io.tmpdir"),
            "agents-flex", "skills-artifact-cache", "s3");
        private AwsCredentialsProvider credentialsProvider;
        private String accessKeyId;
        private String accessKeySecret;
        private String securityToken;
        private long maxPackageSize = ObjectStorageSkillArtifactStore.DEFAULT_MAX_PACKAGE_SIZE;

        public Builder region(String region) {
            this.region = region;
            return this;
        }

        public Builder endpoint(String endpoint) {
            this.endpoint = isBlank(endpoint) ? null : URI.create(endpoint.trim());
            return this;
        }

        public Builder endpoint(URI endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public Builder forcePathStyle(boolean forcePathStyle) {
            this.forcePathStyle = forcePathStyle;
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

        public Builder credentialsProvider(AwsCredentialsProvider credentialsProvider) {
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

        public S3SkillArtifactStore build() {
            validate();
            S3ClientBuilder clientBuilder = S3Client.builder()
                .region(Region.of(region.trim()))
                .credentialsProvider(resolveCredentialsProvider())
                .forcePathStyle(forcePathStyle);
            if (endpoint != null) {
                clientBuilder.endpointOverride(endpoint);
            }
            S3Client client = clientBuilder.build();
            try {
                return new S3SkillArtifactStore(client, true, bucket, keyPrefix,
                    cacheDirectory, maxPackageSize);
            } catch (RuntimeException e) {
                client.close();
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
            if (endpoint != null && (!endpoint.isAbsolute()
                || endpoint.getHost() == null
                || !("http".equalsIgnoreCase(endpoint.getScheme())
                || "https".equalsIgnoreCase(endpoint.getScheme())))) {
                throw new IllegalArgumentException("endpoint must be an absolute HTTP or HTTPS URI");
            }
            if (maxPackageSize <= 0) {
                throw new IllegalArgumentException("maxPackageSize must be greater than zero");
            }
        }

        private AwsCredentialsProvider resolveCredentialsProvider() {
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
                return DefaultCredentialsProvider.builder().build();
            }
            if (!hasAccessKeyId || !hasAccessKeySecret) {
                throw new IllegalArgumentException(
                    "accessKeyId and accessKeySecret must be configured together");
            }
            if (hasSecurityToken) {
                return StaticCredentialsProvider.create(
                    AwsSessionCredentials.create(accessKeyId, accessKeySecret, securityToken));
            }
            return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKeyId, accessKeySecret));
        }

        private boolean isBlank(String value) {
            return value == null || value.trim().isEmpty();
        }
    }
}

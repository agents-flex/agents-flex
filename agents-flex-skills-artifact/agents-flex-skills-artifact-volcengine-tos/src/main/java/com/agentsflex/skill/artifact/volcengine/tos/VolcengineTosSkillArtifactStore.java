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
package com.agentsflex.skill.artifact.volcengine.tos;

import com.agentsflex.skill.artifact.objectstorage.ObjectStorageSkillArtifactStore;
import com.volcengine.tos.TOSV2;
import com.volcengine.tos.TOSV2ClientBuilder;
import com.volcengine.tos.credential.CredentialsProvider;
import com.volcengine.tos.credential.EnvCredentialsProvider;
import com.volcengine.tos.credential.StaticCredentialsProvider;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/** 使用火山引擎 TOS Java SDK 的 Skill Artifact Store。 */
public class VolcengineTosSkillArtifactStore extends ObjectStorageSkillArtifactStore {

    /** 使用调用方管理生命周期的 TOSV2 Client。 */
    public VolcengineTosSkillArtifactStore(TOSV2 client, String bucket, String keyPrefix, Path cacheDirectory) {
        this(client, false, bucket, keyPrefix, cacheDirectory,
            ObjectStorageSkillArtifactStore.DEFAULT_MAX_PACKAGE_SIZE);
    }

    /** 使用可选自主管理生命周期的 TOSV2 Client。 */
    public VolcengineTosSkillArtifactStore(TOSV2 client, boolean closeClient, String bucket, String keyPrefix,
                                 Path cacheDirectory, long maxPackageSize) {
        super(new VolcengineTosObjectStorageOperations(requireClient(client), closeClient),
            bucket, keyPrefix, cacheDirectory, maxPackageSize);
    }

    public static Builder builder() {
        return new Builder();
    }

    private static TOSV2 requireClient(TOSV2 client) {
        if (client == null) {
            throw new IllegalArgumentException("client must not be null");
        }
        return client;
    }

    /** TOS Skill Artifact Store 构建器。 */
    public static class Builder {

        private String region;
        private String endpoint;
        private String bucket;
        private String keyPrefix = "agents-flex/skills";
        private Path cacheDirectory = Paths.get(System.getProperty("java.io.tmpdir"),
            "agents-flex", "skills-artifact-cache", "tos");
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

        public VolcengineTosSkillArtifactStore build() {
            validate();
            TOSV2 client = new TOSV2ClientBuilder().build(
                region.trim(), endpoint.trim(), resolveCredentialsProvider());
            try {
                return new VolcengineTosSkillArtifactStore(client, true, bucket, keyPrefix,
                    cacheDirectory, maxPackageSize);
            } catch (RuntimeException e) {
                try {
                    client.close();
                } catch (IOException closeError) {
                    e.addSuppressed(closeError);
                }
                throw e;
            }
        }

        private void validate() {
            if (isBlank(region)) {
                throw new IllegalArgumentException("region must not be blank");
            }
            if (isBlank(endpoint)) {
                throw new IllegalArgumentException("endpoint must not be blank");
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
                return new EnvCredentialsProvider();
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

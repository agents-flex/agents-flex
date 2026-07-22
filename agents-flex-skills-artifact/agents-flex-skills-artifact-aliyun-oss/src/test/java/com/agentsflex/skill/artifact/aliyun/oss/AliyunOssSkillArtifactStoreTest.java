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
package com.agentsflex.skill.artifact.aliyun.oss;

import com.aliyun.sdk.service.oss2.credentials.StaticCredentialsProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AliyunOssSkillArtifactStoreTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void builderSupportsStaticAccessKeyAndStsCredentials() throws Exception {
        try (AliyunOssSkillArtifactStore ignored = AliyunOssSkillArtifactStore.builder()
            .region("cn-hangzhou")
            .bucket("skills-bucket")
            .cacheDirectory(temporaryFolder.newFolder("ak-cache").toPath())
            .credentials("access-key-id", "access-key-secret")
            .build()) {
            assertNotNull(ignored);
        }

        try (AliyunOssSkillArtifactStore ignored = AliyunOssSkillArtifactStore.builder()
            .region("cn-hangzhou")
            .bucket("skills-bucket")
            .cacheDirectory(temporaryFolder.newFolder("sts-cache").toPath())
            .accessKeyId("access-key-id")
            .accessKeySecret("access-key-secret")
            .securityToken("security-token")
            .build()) {
            assertNotNull(ignored);
        }
    }

    @Test
    public void builderRejectsIncompleteOrAmbiguousCredentials() throws Exception {
        try {
            AliyunOssSkillArtifactStore.builder()
                .region("cn-hangzhou")
                .bucket("skills-bucket")
                .cacheDirectory(temporaryFolder.newFolder("incomplete-cache").toPath())
                .accessKeyId("access-key-id")
                .build();
            fail("Expected incomplete AccessKey credentials to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("must be configured together"));
        }

        try {
            AliyunOssSkillArtifactStore.builder()
                .region("cn-hangzhou")
                .bucket("skills-bucket")
                .cacheDirectory(temporaryFolder.newFolder("ambiguous-cache").toPath())
                .credentialsProvider(new StaticCredentialsProvider("provider-id", "provider-secret"))
                .credentials("access-key-id", "access-key-secret")
                .build();
            fail("Expected ambiguous credentials to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("must not be configured together"));
        }
    }
}

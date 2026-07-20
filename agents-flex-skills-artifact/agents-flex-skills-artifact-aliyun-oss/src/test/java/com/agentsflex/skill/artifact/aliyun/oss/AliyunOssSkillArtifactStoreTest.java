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

package com.agentsflex.skill.artifact.huawei.obs;

import com.obs.services.BasicObsCredentialsProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class HuaweiObsSkillArtifactStoreTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void builderSupportsStaticAccessKeyAndStsCredentials() throws Exception {
        try (HuaweiObsSkillArtifactStore ignored = baseBuilder("ak-cache")
            .credentials("access-key-id", "access-key-secret")
            .build()) {
            assertNotNull(ignored);
        }

        try (HuaweiObsSkillArtifactStore ignored = baseBuilder("sts-cache")
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
            baseBuilder("incomplete-cache").accessKeySecret("access-key-secret").build();
            fail("Expected incomplete AccessKey credentials to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("must be configured together"));
        }

        try {
            baseBuilder("ambiguous-cache")
                .credentialsProvider(new BasicObsCredentialsProvider("provider-id", "provider-secret"))
                .credentials("access-key-id", "access-key-secret")
                .build();
            fail("Expected ambiguous credentials to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("must not be configured together"));
        }
    }

    private HuaweiObsSkillArtifactStore.Builder baseBuilder(String cacheName) throws Exception {
        return HuaweiObsSkillArtifactStore.builder()
            .endpoint("https://obs.cn-north-4.myhuaweicloud.com")
            .bucket("skills-bucket")
            .cacheDirectory(temporaryFolder.newFolder(cacheName).toPath());
    }
}

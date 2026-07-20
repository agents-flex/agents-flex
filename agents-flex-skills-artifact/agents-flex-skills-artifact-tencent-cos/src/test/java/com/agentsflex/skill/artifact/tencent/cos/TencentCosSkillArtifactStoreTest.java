package com.agentsflex.skill.artifact.tencent.cos;

import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.auth.COSCredentialsProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TencentCosSkillArtifactStoreTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void builderSupportsStaticAccessKeyAndStsCredentials() throws Exception {
        try (TencentCosSkillArtifactStore ignored = baseBuilder("ak-cache")
            .credentials("access-key-id", "access-key-secret")
            .build()) {
            assertNotNull(ignored);
        }

        try (TencentCosSkillArtifactStore ignored = baseBuilder("sts-cache")
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
            baseBuilder("incomplete-cache").accessKeyId("access-key-id").build();
            fail("Expected incomplete AccessKey credentials to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("must be configured together"));
        }

        try {
            baseBuilder("ambiguous-cache")
                .credentialsProvider(new StaticProvider())
                .credentials("access-key-id", "access-key-secret")
                .build();
            fail("Expected ambiguous credentials to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("must not be configured together"));
        }
    }

    private TencentCosSkillArtifactStore.Builder baseBuilder(String cacheName) throws Exception {
        return TencentCosSkillArtifactStore.builder()
            .region("ap-guangzhou")
            .bucket("skills-bucket-1250000000")
            .cacheDirectory(temporaryFolder.newFolder(cacheName).toPath());
    }

    private static class StaticProvider implements COSCredentialsProvider {
        @Override
        public COSCredentials getCredentials() {
            return new BasicCOSCredentials("provider-id", "provider-secret");
        }

        @Override
        public void refresh() {
        }
    }
}

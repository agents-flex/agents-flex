package com.agentsflex.skill.artifact.volcengine.tos;

import com.volcengine.tos.credential.StaticCredentialsProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class VolcengineTosSkillArtifactStoreTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void builderSupportsStaticAccessKeyAndStsCredentials() throws Exception {
        try (VolcengineTosSkillArtifactStore ignored = baseBuilder("ak-cache")
            .credentials("access-key-id", "access-key-secret")
            .build()) {
            assertNotNull(ignored);
        }

        try (VolcengineTosSkillArtifactStore ignored = baseBuilder("sts-cache")
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
            baseBuilder("incomplete-cache").securityToken("security-token").build();
            fail("Expected incomplete AccessKey credentials to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("must be configured together"));
        }

        try {
            baseBuilder("ambiguous-cache")
                .credentialsProvider(new StaticCredentialsProvider("provider-id", "provider-secret"))
                .credentials("access-key-id", "access-key-secret")
                .build();
            fail("Expected ambiguous credentials to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("must not be configured together"));
        }
    }

    private VolcengineTosSkillArtifactStore.Builder baseBuilder(String cacheName) throws Exception {
        return VolcengineTosSkillArtifactStore.builder()
            .region("cn-beijing")
            .endpoint("https://tos-cn-beijing.volces.com")
            .bucket("skills-bucket")
            .cacheDirectory(temporaryFolder.newFolder(cacheName).toPath());
    }
}

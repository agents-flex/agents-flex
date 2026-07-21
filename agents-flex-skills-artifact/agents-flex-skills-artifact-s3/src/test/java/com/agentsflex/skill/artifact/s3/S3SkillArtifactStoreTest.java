package com.agentsflex.skill.artifact.s3;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import java.net.URI;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class S3SkillArtifactStoreTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void builderSupportsAwsAndS3CompatibleConfiguration() throws Exception {
        try (S3SkillArtifactStore ignored = baseBuilder("aws-cache")
            .credentials("access-key-id", "access-key-secret")
            .build()) {
            assertNotNull(ignored);
        }

        try (S3SkillArtifactStore ignored = baseBuilder("rustfs-cache")
            .endpoint(URI.create("http://127.0.0.1:9000"))
            .forcePathStyle(true)
            .credentials("access-key-id", "access-key-secret", "security-token")
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
                .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("provider-id", "provider-secret")))
                .credentials("access-key-id", "access-key-secret")
                .build();
            fail("Expected ambiguous credentials to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("must not be configured together"));
        }
    }

    @Test
    public void builderRejectsRelativeEndpoint() throws Exception {
        try {
            baseBuilder("endpoint-cache").endpoint(URI.create("localhost:9000")).build();
            fail("Expected relative endpoint to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("absolute HTTP or HTTPS URI"));
        }
    }

    private S3SkillArtifactStore.Builder baseBuilder(String cacheName) throws Exception {
        return S3SkillArtifactStore.builder()
            .region("us-east-1")
            .bucket("skills-bucket")
            .cacheDirectory(temporaryFolder.newFolder(cacheName).toPath());
    }
}

package com.agentsflex.skill.artifact.aliyun.oss;

import com.agentsflex.skill.artifact.PathSkillPackage;
import com.agentsflex.skill.artifact.SkillArtifact;
import com.agentsflex.skill.artifact.SkillInstallRequest;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.Console;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AliyunOssSkillArtifactStoreIntegrationTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

//    @Test
    public void installsMaterializesAndDeletesArtifactInOss() throws Exception {
        String accessKeyId = credential("AF_OSS_ACCESS_KEY_ID", "OSS AccessKey ID: ");
        String accessKeySecret = credential("AF_OSS_ACCESS_KEY_SECRET", "OSS AccessKey Secret: ");
        String region = "cn-beijing";//System.getProperty("af.oss.region");
        String endpoint = "https://oss-cn-beijing.aliyuncs.com";
        String bucket = "agents-flex-skills-artifact";
        Assume.assumeTrue("OSS integration test credentials and target bucket are required",
            !isBlank(accessKeyId) && !isBlank(accessKeySecret)
                && !isBlank(region) && !isBlank(endpoint) && !isBlank(bucket));

        String runId = UUID.randomUUID().toString();
        Path packagePath = createSkillPackage();
        SkillArtifact installed = null;
        try (AliyunOssSkillArtifactStore store = AliyunOssSkillArtifactStore.builder()
            .region(region)
            .endpoint(endpoint)
            .bucket(bucket)
            .keyPrefix("agents-flex/integration-tests/" + runId)
            .cacheDirectory(temporaryFolder.newFolder("cache").toPath())
            .credentials(accessKeyId, accessKeySecret)
            .build()) {
            try {
                installed = store.install(new SkillInstallRequest(
                    new SkillArtifact("oss-integration-test", runId, null, null),
                    new PathSkillPackage(packagePath)));

                assertTrue(installed.getDigest().matches("sha256:[0-9a-f]{64}"));
                assertTrue(installed.getStorageKey().startsWith(
                    "agents-flex/integration-tests/" + runId + "/"));

                Path materialized = store.materialize(installed);
                assertEquals(skillDefinition(), new String(
                    Files.readAllBytes(materialized.resolve("SKILL.md")), StandardCharsets.UTF_8));
            } finally {
                if (installed != null) {
                    store.delete(installed);
                }
            }
        }
    }

    private Path createSkillPackage() throws Exception {
        Path zipPath = temporaryFolder.newFile("oss-integration-test.zip").toPath();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            zip.putNextEntry(new ZipEntry("SKILL.md"));
            zip.write(skillDefinition().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return zipPath;
    }

    private String skillDefinition() {
        return "---\nname: oss-integration-test\n---\nAliyun OSS integration test";
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String credential(String environmentVariable, String prompt) {
        String value = System.getenv(environmentVariable);
        if (!isBlank(value) || !Boolean.getBoolean("af.oss.interactive")) {
            return value;
        }
        Console console = System.console();
        Assume.assumeNotNull(console);
        char[] input = console.readPassword(prompt);
        return input == null ? null : new String(input);
    }
}

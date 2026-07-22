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
package com.agentsflex.skill.artifact.objectstorage;

import com.agentsflex.skill.artifact.PathSkillPackage;
import com.agentsflex.skill.artifact.SkillArtifact;
import com.agentsflex.skill.artifact.SkillArtifactStoreException;
import com.agentsflex.skill.artifact.SkillInstallRequest;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ObjectStorageSkillArtifactStoreTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void installsMaterializesCachesAndDeletesArtifact() throws Exception {
        InMemoryObjectStorage operations = new InMemoryObjectStorage();
        File cache = temporaryFolder.newFolder("cache");
        Path packagePath = createSkillPackage("pdf.zip");
        ObjectStorageSkillArtifactStore store = new ObjectStorageSkillArtifactStore(
            operations, "skills-bucket", "artifacts", cache.toPath(), 1024 * 1024);
        SkillArtifact requested = new SkillArtifact("pdf", "1.0.0", null, null);

        SkillArtifact installed = store.install(new SkillInstallRequest(
            requested, new PathSkillPackage(packagePath)));

        assertTrue(installed.getDigest().matches("sha256:[0-9a-f]{64}"));
        assertTrue(installed.getStorageKey().matches("artifacts/pdf/1.0.0/[0-9a-f]{64}\\.zip"));
        assertTrue(installed.getSize() > 0);
        assertNotNull(operations.objects.get(objectId("skills-bucket", installed.getStorageKey())));

        Path localDirectory = store.materialize(installed);
        assertTrue(Files.isRegularFile(localDirectory.resolve("SKILL.md")));
        assertTrue(Files.isRegularFile(localDirectory.resolve("scripts/run.sh")));
        assertEquals(1, operations.getCount);

        assertEquals(localDirectory, store.materialize(installed));
        assertEquals(1, operations.getCount);

        store.delete(installed);
        assertFalse(operations.objects.containsKey(objectId("skills-bucket", installed.getStorageKey())));
        assertFalse(Files.exists(localDirectory));
        store.delete(installed);
    }

    @Test
    public void rejectsDeclaredDigestMismatchBeforeUpload() throws Exception {
        InMemoryObjectStorage operations = new InMemoryObjectStorage();
        Path packagePath = createSkillPackage("pdf.zip");
        ObjectStorageSkillArtifactStore store = new ObjectStorageSkillArtifactStore(
            operations, "skills-bucket", "artifacts",
            temporaryFolder.newFolder("cache").toPath(), 1024 * 1024);
        SkillArtifact artifact = new SkillArtifact("pdf", "1.0.0",
            "sha256:0000000000000000000000000000000000000000000000000000000000000000", null);

        try {
            store.install(new SkillInstallRequest(artifact, new PathSkillPackage(packagePath)));
            fail("Expected digest mismatch to fail");
        } catch (SkillArtifactStoreException expected) {
            assertTrue(expected.getMessage().contains("digest does not match"));
        }
        assertTrue(operations.objects.isEmpty());
    }

    @Test
    public void rejectsTamperedDownloadedObject() throws Exception {
        InMemoryObjectStorage operations = new InMemoryObjectStorage();
        Path packagePath = createSkillPackage("pdf.zip");
        ObjectStorageSkillArtifactStore store = new ObjectStorageSkillArtifactStore(
            operations, "skills-bucket", "artifacts",
            temporaryFolder.newFolder("cache").toPath(), 1024 * 1024);
        SkillArtifact installed = store.install(new SkillInstallRequest(
            new SkillArtifact("pdf", "1.0.0", null, null), new PathSkillPackage(packagePath)));
        operations.objects.put(objectId("skills-bucket", installed.getStorageKey()),
            "tampered".getBytes(StandardCharsets.UTF_8));

        try {
            store.materialize(installed);
            fail("Expected downloaded digest mismatch to fail");
        } catch (SkillArtifactStoreException expected) {
            assertTrue(expected.getMessage().contains("digest does not match"));
        }
    }

    @Test
    public void rejectsPackageWhoseSkillNameDoesNotMatchArtifact() throws Exception {
        InMemoryObjectStorage operations = new InMemoryObjectStorage();
        Path packagePath = createSkillPackage("pdf.zip");
        ObjectStorageSkillArtifactStore store = new ObjectStorageSkillArtifactStore(
            operations, "skills-bucket", "artifacts",
            temporaryFolder.newFolder("cache").toPath(), 1024 * 1024);

        try {
            store.install(new SkillInstallRequest(
                new SkillArtifact("xlsx", "1.0.0", null, null), new PathSkillPackage(packagePath)));
            fail("Expected skill name mismatch to fail");
        } catch (SkillArtifactStoreException expected) {
            assertTrue(expected.getMessage().contains("matching SKILL.md"));
        }
        assertTrue(operations.objects.isEmpty());
    }

    private Path createSkillPackage(String fileName) throws Exception {
        Path zipPath = temporaryFolder.newFile(fileName).toPath();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            writeEntry(zip, "SKILL.md", "---\nname: pdf\n---\nUse PDF skill");
            writeEntry(zip, "scripts/run.sh", "#!/bin/sh\n");
        }
        return zipPath;
    }

    private void writeEntry(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String objectId(String bucket, String key) {
        return bucket + "/" + key;
    }

    private static class InMemoryObjectStorage implements ObjectStorageOperations {

        private final Map<String, byte[]> objects = new HashMap<>();
        private int getCount;

        @Override
        public void put(String bucket, String key, InputStream inputStream, long contentLength) {
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                byte[] value = output.toByteArray();
                assertEquals(contentLength, value.length);
                objects.put(objectId(bucket, key), value);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public InputStream get(String bucket, String key) {
            getCount++;
            byte[] value = objects.get(objectId(bucket, key));
            if (value == null) {
                throw new IllegalStateException("Object not found");
            }
            return new ByteArrayInputStream(value);
        }

        @Override
        public void delete(String bucket, String key) {
            objects.remove(objectId(bucket, key));
        }

        @Override
        public void close() {
        }
    }
}

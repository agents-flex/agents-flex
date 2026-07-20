package com.agentsflex.skill.artifact;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class FileSystemSkillArtifactStoreTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void materializesRelativeSkillDirectory() throws Exception {
        File skillDirectory = temporaryFolder.newFolder("installed", "pdf");
        Files.write(new File(skillDirectory, "SKILL.md").toPath(),
            "---\nname: pdf\n---\n".getBytes(StandardCharsets.UTF_8));
        Path root = temporaryFolder.getRoot().toPath().resolve("installed");
        FileSystemSkillArtifactStore store = new FileSystemSkillArtifactStore(root);

        Path materialized = store.materialize(
            new SkillArtifact("pdf", "1.0.0", "sha256-test", "pdf"));

        assertEquals(skillDirectory.toPath().toRealPath(), materialized);
    }

    @Test
    public void rejectsStorageKeyOutsideConfiguredRoot() throws Exception {
        File root = temporaryFolder.newFolder("installed");
        FileSystemSkillArtifactStore store = new FileSystemSkillArtifactStore(root.toPath());

        try {
            store.materialize(new SkillArtifact("pdf", "1.0.0", "sha256-test", "../pdf"));
            fail("Expected path escape to fail");
        } catch (SkillArtifactStoreException expected) {
            assertTrue(expected.getMessage().contains("escapes"));
        }
    }

    @Test
    public void requiresSkillDefinitionAtArtifactRoot() throws Exception {
        File root = temporaryFolder.newFolder("installed");
        temporaryFolder.newFolder("installed", "pdf");
        FileSystemSkillArtifactStore store = new FileSystemSkillArtifactStore(root.toPath());

        try {
            store.materialize(new SkillArtifact("pdf", "1.0.0", "sha256-test", "pdf"));
            fail("Expected missing SKILL.md to fail");
        } catch (SkillArtifactStoreException expected) {
            assertTrue(expected.getMessage().contains("SKILL.md"));
        }
    }

    @Test
    public void installsMaterializesAndDeletesArtifact() throws Exception {
        File root = temporaryFolder.newFolder("installed");
        Path skillPackage = createSkillPackage("pdf.zip");
        FileSystemSkillArtifactStore store = new FileSystemSkillArtifactStore(root.toPath());
        SkillArtifact artifact = new SkillArtifact(
            "pdf", "1.0.0", "sha256-test", "pdf/1.0.0");

        SkillInstallRequest request = new SkillInstallRequest(artifact, new PathSkillPackage(skillPackage));
        assertEquals(artifact, store.install(request));
        Path installed = store.materialize(artifact);
        assertTrue(Files.isRegularFile(installed.resolve("scripts/run.sh")));

        store.delete(artifact);
        assertFalse(Files.exists(installed));
        store.delete(artifact);
    }

    @Test
    public void refusesToOverwriteInstalledArtifact() throws Exception {
        File root = temporaryFolder.newFolder("installed");
        Path skillPackage = createSkillPackage("pdf.zip");
        FileSystemSkillArtifactStore store = new FileSystemSkillArtifactStore(root.toPath());
        SkillArtifact artifact = new SkillArtifact(
            "pdf", "1.0.0", "sha256-test", "pdf/1.0.0");
        SkillInstallRequest request = new SkillInstallRequest(artifact, new PathSkillPackage(skillPackage));
        store.install(request);

        try {
            store.install(request);
            fail("Expected duplicate installation to fail");
        } catch (SkillArtifactStoreException expected) {
            assertTrue(expected.getMessage().contains("already exists"));
        }
    }

    @Test
    public void rejectsZipEntryOutsideInstallationDirectory() throws Exception {
        File root = temporaryFolder.newFolder("installed");
        Path zipPath = temporaryFolder.newFile("unsafe.zip").toPath();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            writeEntry(zip, "../outside.txt", "unsafe");
        }
        FileSystemSkillArtifactStore store = new FileSystemSkillArtifactStore(root.toPath());
        SkillArtifact artifact = new SkillArtifact(
            "pdf", "1.0.0", "sha256-test", "pdf/1.0.0");

        try {
            store.install(new SkillInstallRequest(artifact, new PathSkillPackage(zipPath)));
            fail("Expected Zip Slip entry to fail");
        } catch (SkillArtifactStoreException expected) {
            assertTrue(expected.getMessage().contains("escapes"));
        }
        assertFalse(Files.exists(root.toPath().resolve("pdf/1.0.0")));
    }

    private Path createSkillPackage(String fileName) throws Exception {
        Path zipPath = temporaryFolder.newFile(fileName).toPath();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            writeEntry(zip, "SKILL.md", "---\nname: pdf\n---\n");
            writeEntry(zip, "scripts/run.sh", "#!/bin/sh\n");
        }
        return zipPath;
    }

    private void writeEntry(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}

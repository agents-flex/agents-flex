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
package com.agentsflex.skill;

import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.skill.artifact.SkillArtifact;
import com.agentsflex.skill.artifact.SkillArtifactStore;
import com.agentsflex.skill.artifact.SkillInstallRequest;
import com.agentsflex.skill.runtime.SkillExecutionRequest;
import com.agentsflex.skill.runtime.SkillExecutionResult;
import com.agentsflex.skill.runtime.SkillPreparationRequest;
import com.agentsflex.skill.runtime.SkillRuntime;
import com.agentsflex.skill.runtime.SkillRuntimeFileSystem;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SkillsToolTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void selectedSkillsAreFilteredBeforeRuntimePreparation() throws Exception {
        createSkill("pdf", "PDF documents");
        createSkill("xlsx", "Excel workbooks");
        createSkill("pptx", "PowerPoint presentations");
        RecordingRuntime runtime = new RecordingRuntime();

        Tool skillTool = SkillsTool.builder()
            .addSkillsDirectory(temporaryFolder.getRoot().getAbsolutePath(), "pdf", "pptx")
            .runtime(runtime)
            .build();

        assertEquals(Arrays.asList("pdf", "pptx"), runtime.preparedSkillNames);
        assertTrue(skillTool.getDescription().contains("<name>pdf</name>"));
        assertTrue(skillTool.getDescription().contains("<name>pptx</name>"));
        assertFalse(skillTool.getDescription().contains("<name>xlsx</name>"));
        assertEquals("Skill not found: xlsx", skillTool.invoke(
            Collections.<String, Object>singletonMap("command", "xlsx")));
    }

    @Test
    public void missingSelectedSkillFailsFast() throws Exception {
        createSkill("pdf", "PDF documents");

        try {
            SkillsTool.builder().addSkillsDirectory(
                temporaryFolder.getRoot().getAbsolutePath(), "pdf", "xlsx");
            fail("Expected missing skill selection to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("xlsx"));
        }
    }

    @Test
    public void duplicateSelectedSkillNameFailsFast() throws Exception {
        createSkill("first", "shared", "First definition");
        createSkill("second", "shared", "Second definition");

        try {
            SkillsTool.builder().addSkillsDirectory(
                temporaryFolder.getRoot().getAbsolutePath(), "shared");
            fail("Expected duplicate skill selection to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Multiple skills named 'shared'"));
        }
    }

    @Test
    public void artifactIsMaterializedBeforeRuntimePreparation() throws Exception {
        File skillDirectory = createSkill("pdf", "PDF documents");
        SkillArtifact artifact = new SkillArtifact(
            "pdf", "1.0.0", "sha256-test", "skills/pdf");
        RecordingArtifactStore artifactStore = new RecordingArtifactStore(skillDirectory.toPath());
        RecordingRuntime runtime = new RecordingRuntime();

        SkillsTool.builder()
            .addSkillArtifact(artifactStore, artifact)
            .runtime(runtime)
            .build();

        assertEquals(artifact, artifactStore.materializedArtifact);
        assertEquals(Collections.singletonList("pdf"), runtime.preparedSkillNames);
    }

    @Test
    public void artifactNameMustMatchSkillDefinition() throws Exception {
        File skillDirectory = createSkill("pdf", "PDF documents");
        SkillArtifact artifact = new SkillArtifact(
            "xlsx", "1.0.0", "sha256-test", "skills/pdf");

        try {
            SkillsTool.builder().addSkillArtifact(
                new RecordingArtifactStore(skillDirectory.toPath()), artifact);
            fail("Expected artifact name mismatch to fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("xlsx"));
        }
    }

    @Test
    public void artifactStoreMustReturnLocalDirectory() {
        SkillArtifact artifact = new SkillArtifact(
            "pdf", "1.0.0", "sha256-test", "skills/pdf");

        try {
            SkillsTool.builder().addSkillArtifact(new SkillArtifactStore() {
                @Override
                public SkillArtifact install(SkillInstallRequest request) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Path materialize(SkillArtifact ignored) {
                    return null;
                }

                @Override
                public void delete(SkillArtifact installedArtifact) {
                    throw new UnsupportedOperationException();
                }
            }, artifact);
            fail("Expected null materialized directory to fail");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("must not return null"));
        }
    }

    private File createSkill(String name, String description) throws Exception {
        return createSkill(name, name, description);
    }

    private File createSkill(String directoryName, String name, String description) throws Exception {
        File directory = temporaryFolder.newFolder(directoryName);
        String markdown = "---\nname: " + name + "\ndescription: " + description
            + "\n---\nUse the " + name + " skill";
        Files.write(new File(directory, "SKILL.md").toPath(), markdown.getBytes(StandardCharsets.UTF_8));
        return directory;
    }

    private static class RecordingArtifactStore implements SkillArtifactStore {

        private final Path localDirectory;
        private SkillArtifact materializedArtifact;

        private RecordingArtifactStore(Path localDirectory) {
            this.localDirectory = localDirectory;
        }

        @Override
        public SkillArtifact install(SkillInstallRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Path materialize(SkillArtifact artifact) {
            this.materializedArtifact = artifact;
            return localDirectory;
        }

        @Override
        public void delete(SkillArtifact artifact) {
            throw new UnsupportedOperationException();
        }
    }

    private static class RecordingRuntime implements SkillRuntime {

        private List<String> preparedSkillNames = Collections.emptyList();

        @Override
        public String getName() {
            return "recording";
        }

        @Override
        public List<Skill> prepare(SkillPreparationRequest request) {
            List<String> names = new ArrayList<>();
            for (Skill skill : request.getSkills()) {
                names.add(skill.name());
            }
            Collections.sort(names);
            this.preparedSkillNames = names;
            return new ArrayList<>(request.getSkills());
        }

        @Override
        public String getDefaultWorkingDirectory() {
            return temporaryDirectory();
        }

        @Override
        public SkillRuntimeFileSystem getFileSystem() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SkillExecutionResult execute(SkillExecutionRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }

        private String temporaryDirectory() {
            return System.getProperty("java.io.tmpdir");
        }
    }
}

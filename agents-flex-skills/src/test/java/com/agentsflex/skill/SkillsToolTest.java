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
import com.agentsflex.skill.runtime.SkillRuntimeConfig;
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
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SkillsToolTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void selectedSkillsAreAdvertisedWithoutPreparingRuntime() throws Exception {
        createSkill("pdf", "PDF documents");
        createSkill("xlsx", "Excel workbooks");
        createSkill("pptx", "PowerPoint presentations");
        RecordingRuntime runtime = new RecordingRuntime();

        Tool skillTool = SkillsTool.builder()
            .addSkillsDirectory(temporaryFolder.getRoot().getAbsolutePath(), "pdf", "pptx")
            .runtime(runtime)
            .build();

        assertTrue(runtime.preparedSkillNames.isEmpty());
        assertTrue(skillTool.getDescription().contains("<name>pdf</name>"));
        assertTrue(skillTool.getDescription().contains("<name>pptx</name>"));
        assertFalse(skillTool.getDescription().contains("<name>xlsx</name>"));
        assertTrue(skillTool.getDescription().contains("use it by default as the final delivery step"));
        assertTrue(skillTool.getDescription().contains("even if the user did not explicitly ask"));
        assertTrue(skillTool.getDescription().contains("send, give, attach, open, download, or share"));
        assertTrue(skillTool.getDescription().contains("never an internal runtime path"));
        assertTrue(skillTool.getDescription().contains("user explicitly asks you not to"));
        assertEquals("Skill not found: xlsx", skillTool.invoke(
            Collections.<String, Object>singletonMap("command", "xlsx")));
        assertTrue(runtime.preparedSkillNames.isEmpty());

        Object result = skillTool.invoke(Collections.<String, Object>singletonMap("command", "pptx"));
        assertTrue(result.toString().contains("/runtime/pptx"));
        assertEquals(Collections.singletonList("pptx"), runtime.preparedSkillNames);
        assertEquals(1, runtime.prepareCalls);
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
    public void artifactIsMaterializedBeforeLazyRuntimePreparation() throws Exception {
        File skillDirectory = createSkill("pdf", "PDF documents");
        SkillArtifact artifact = new SkillArtifact(
            "pdf", "1.0.0", "sha256-test", "skills/pdf");
        RecordingArtifactStore artifactStore = new RecordingArtifactStore(skillDirectory.toPath());
        RecordingRuntime runtime = new RecordingRuntime();

        Tool skillTool = SkillsTool.builder()
            .addSkillArtifact(artifactStore, artifact)
            .runtime(runtime)
            .build();

        assertEquals(artifact, artifactStore.materializedArtifact);
        assertTrue(runtime.preparedSkillNames.isEmpty());
        skillTool.invoke(Collections.<String, Object>singletonMap("command", "pdf"));
        assertEquals(Collections.singletonList("pdf"), runtime.preparedSkillNames);
    }

    @Test
    public void successfulPreparationIsCachedPerSkill() throws Exception {
        createSkill("pdf", "PDF documents");
        RecordingRuntime runtime = new RecordingRuntime();
        Tool skillTool = SkillsTool.builder()
            .addSkillsDirectory(temporaryFolder.getRoot().getAbsolutePath())
            .runtime(runtime)
            .build();

        Map<String, Object> command = Collections.<String, Object>singletonMap("command", "pdf");
        skillTool.invoke(command);
        skillTool.invoke(command);

        assertEquals(1, runtime.prepareCalls);
    }

    @Test
    public void runtimeConfigIsValidatedEagerlyAndAppliedLazily() throws Exception {
        createSkill("pdf", "PDF documents");
        createSkill("pptx", "PowerPoint presentations");
        SkillRuntimeConfig config = SkillRuntimeConfig.builder()
            .environment("PPTX_THEME", "corporate")
            .build();
        RecordingRuntime runtime = new RecordingRuntime();
        Tool skillTool = SkillsTool.builder()
            .addSkillsDirectory(temporaryFolder.getRoot().getAbsolutePath())
            .skillRuntimeConfig("pptx", config)
            .runtime(runtime)
            .build();

        assertEquals(0, runtime.prepareCalls);
        skillTool.invoke(Collections.<String, Object>singletonMap("command", "pdf"));
        assertTrue(runtime.lastRuntimeConfigs.isEmpty());
        skillTool.invoke(Collections.<String, Object>singletonMap("command", "pptx"));
        assertEquals(config, runtime.lastRuntimeConfigs.get("pptx"));

        try {
            SkillsTool.builder()
                .addSkillsDirectory(temporaryFolder.getRoot().getAbsolutePath())
                .skillRuntimeConfig("unknown", config)
                .runtime(new RecordingRuntime())
                .build();
            fail("Expected an unknown Runtime Config skill to fail during build");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("unknown"));
        }
    }

    @Test
    public void failedPreparationIsRetried() throws Exception {
        createSkill("pdf", "PDF documents");
        RecordingRuntime runtime = new RecordingRuntime();
        runtime.failuresRemaining = 1;
        Tool skillTool = SkillsTool.builder()
            .addSkillsDirectory(temporaryFolder.getRoot().getAbsolutePath())
            .runtime(runtime)
            .build();
        Map<String, Object> command = Collections.<String, Object>singletonMap("command", "pdf");

        try {
            skillTool.invoke(command);
            fail("Expected the first preparation to fail");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("prepare failed"));
        }
        Object result = skillTool.invoke(command);

        assertTrue(result.toString().contains("/runtime/pdf"));
        assertEquals(2, runtime.prepareCalls);
    }

    @Test
    public void concurrentCallsPrepareSkillOnce() throws Exception {
        createSkill("pdf", "PDF documents");
        final RecordingRuntime runtime = new RecordingRuntime();
        final Tool skillTool = SkillsTool.builder()
            .addSkillsDirectory(temporaryFolder.getRoot().getAbsolutePath())
            .runtime(runtime)
            .build();
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread first = invokeSkill(start, failure, skillTool, "pdf");
        Thread second = invokeSkill(start, failure, skillTool, "pdf");

        first.start();
        second.start();
        start.countDown();
        first.join(TimeUnit.SECONDS.toMillis(5));
        second.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        assertEquals(1, runtime.prepareCalls);
    }

    private Thread invokeSkill(final CountDownLatch start, final AtomicReference<Throwable> failure,
                               final Tool skillTool, final String skillName) {
        return new Thread(() -> {
            try {
                start.await();
                skillTool.invoke(Collections.<String, Object>singletonMap("command", skillName));
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
        });
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
        private Map<String, SkillRuntimeConfig> lastRuntimeConfigs = Collections.emptyMap();
        private int prepareCalls;
        private int failuresRemaining;

        @Override
        public String getName() {
            return "recording";
        }

        @Override
        public synchronized List<Skill> prepare(SkillPreparationRequest request) {
            prepareCalls++;
            if (failuresRemaining > 0) {
                failuresRemaining--;
                throw new IllegalStateException("prepare failed");
            }
            lastRuntimeConfigs = request.getRuntimeConfigs();
            List<String> names = new ArrayList<>();
            List<Skill> prepared = new ArrayList<>();
            for (Skill skill : request.getSkills()) {
                names.add(skill.name());
                prepared.add(new Skill("/runtime/" + skill.name(), skill.getFrontMatter(), skill.getContent()));
            }
            Collections.sort(names);
            this.preparedSkillNames = names;
            return prepared;
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

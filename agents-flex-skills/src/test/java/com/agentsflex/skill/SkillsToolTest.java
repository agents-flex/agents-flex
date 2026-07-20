package com.agentsflex.skill;

import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.skill.runtime.SkillExecutionRequest;
import com.agentsflex.skill.runtime.SkillExecutionResult;
import com.agentsflex.skill.runtime.SkillRuntime;
import com.agentsflex.skill.runtime.SkillRuntimeFileSystem;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    private void createSkill(String name, String description) throws Exception {
        createSkill(name, name, description);
    }

    private void createSkill(String directoryName, String name, String description) throws Exception {
        File directory = temporaryFolder.newFolder(directoryName);
        String markdown = "---\nname: " + name + "\ndescription: " + description
            + "\n---\nUse the " + name + " skill";
        Files.write(new File(directory, "SKILL.md").toPath(), markdown.getBytes(StandardCharsets.UTF_8));
    }

    private static class RecordingRuntime implements SkillRuntime {

        private List<String> preparedSkillNames = Collections.emptyList();

        @Override
        public String getName() {
            return "recording";
        }

        @Override
        public List<Skill> prepare(List<Skill> skills) {
            List<String> names = new ArrayList<>();
            for (Skill skill : skills) {
                names.add(skill.name());
            }
            Collections.sort(names);
            this.preparedSkillNames = names;
            return new ArrayList<>(skills);
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

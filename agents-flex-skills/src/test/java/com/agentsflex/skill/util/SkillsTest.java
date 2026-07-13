package com.agentsflex.skill.util;

import com.agentsflex.skill.Skill;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class SkillsTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void loadsSkillFilesFromDirectory() throws Exception {
        File skillDirectory = temporaryFolder.newFolder("demo");
        File skillFile = new File(skillDirectory, "SKILL.md");
        Files.write(skillFile.toPath(),
            "---\nname: demo\n---\nBody".getBytes(StandardCharsets.UTF_8));

        List<Skill> skills = Skills.loadDirectory(temporaryFolder.getRoot().getAbsolutePath());

        assertEquals(1, skills.size());
        assertEquals("demo", skills.get(0).name());
        assertEquals("Body", skills.get(0).getContent());
    }
}

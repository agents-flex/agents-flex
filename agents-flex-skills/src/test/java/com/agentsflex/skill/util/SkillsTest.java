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

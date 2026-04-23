/*
 * Copyright 2025 - 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentsflex.skill;


import com.agentsflex.core.model.chat.tool.Parameter;
import com.agentsflex.core.model.chat.tool.Tool;
import com.agentsflex.skill.util.Skills;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author Christian Tzolov
 * @author Micahel Yang
 */
public class SkillsTool {

    private static final String TOOL_DESCRIPTION_TEMPLATE = "Execute a skill within the main conversation\n" +
        "\n" +
        "<skills_instructions>\n" +
        "When users ask you to perform tasks, check if any of the available skills below can help complete the task more effectively. Skills provide specialized capabilities and domain knowledge.\n" +
        "\n" +
        "How to use skills:\n" +
        "- Invoke skills using this tool with the skill name only (no arguments)\n" +
        "- When you invoke a skill, you will see <command-message>The \"{name}\" skill is loading</command-message>\n" +
        "- The skill's prompt will expand and provide detailed instructions on how to complete the task\n" +
        "\n" +
        "NOTE: Response always starts start with the base directory of the skill execution environment. You can use this to retrieve additional files of call shell commands.\n" +
        "Skill description follows after the base directory line.\n" +
        "\n" +
        "Important:\n" +
        "- Only use skills listed in <available_skills> below\n" +
        "- Do not invoke a skill that is already running\n" +
        "</skills_instructions>\n" +
        "\n" +
        "<available_skills>\n" +
        "%s\n" +
        "</available_skills>";


    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private List<Skill> skills = new ArrayList<>();

        private String toolDescriptionTemplate = TOOL_DESCRIPTION_TEMPLATE;

        protected Builder() {

        }

        public Builder toolDescriptionTemplate(String template) {
            this.toolDescriptionTemplate = template;
            return this;
        }


        public Builder addSkillsDirectory(String skillsRootDirectory) {
            this.addSkillsDirectories(Collections.singletonList(skillsRootDirectory));
            return this;
        }

        public Builder addSkillsDirectories(List<String> skillsRootDirectories) {
            for (String skillsRootDirectory : skillsRootDirectories) {
                this.skills.addAll(Skills.loadDirectory(skillsRootDirectory));
            }
            return this;
        }

        public Tool build() {
            String skillsXml = this.skills.stream().map(Skill::toXml).collect(Collectors.joining("\n"));
            return Tool.builder()
                .name("Skill")
                .description(String.format(this.toolDescriptionTemplate, skillsXml))
                .addParameter(
                    Parameter.builder()
                        .name("command")
                        .type("string")
                        .description("The skill name (no arguments). E.g., \"pdf\" or \"xlsx\"").build()
                )
                .function(stringStringMap -> {
                    String command = (String) stringStringMap.get("command");
                    Skill skill = null;
                    for (Skill s : skills) {
                        if (s.name().equals(command)) {
                            skill = s;
                            break;
                        }
                    }

                    if (skill != null) {
                        return String.format("Base directory for this skill: %s\n\n%s", skill.getBasePath(), skill.getContent());
                    }

                    return "Skill not found: " + command;
                }).build();
        }
    }
}

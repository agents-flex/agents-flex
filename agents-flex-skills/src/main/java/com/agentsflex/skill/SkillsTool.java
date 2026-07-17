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
import com.agentsflex.core.model.chat.tool.ToolScanner;
import com.agentsflex.skill.attachment.FilePublisher;
import com.agentsflex.skill.local.LocalSkillRuntime;
import com.agentsflex.skill.runtime.SkillRuntime;
import com.agentsflex.skill.tools.SkillRuntimeFilePublishTools;
import com.agentsflex.skill.tools.SkillRuntimeFileTools;
import com.agentsflex.skill.tools.SkillRuntimeSearchTools;
import com.agentsflex.skill.tools.SkillRuntimeShellTools;
import com.agentsflex.skill.util.Skills;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 将文件系统中的 Skills 注册为模型工具，并统一绑定到一个 {@link SkillRuntime}。
 *
 * <p>该类同时解决两个问题：</p>
 * <ol>
 *     <li>构建 {@code Skill} 工具，让模型先看到可用 Skill 的元数据，并按名称加载
 *     {@code SKILL.md} 正文；</li>
 *     <li>通过 {@link Builder#buildTools()} 注册由同一个 Runtime 驱动的 Bash、文件和
 *     搜索工具，保证 Skill 指令中的后续操作不会意外回到宿主机执行。</li>
 * </ol>
 *
 * <p>默认使用 {@link LocalSkillRuntime}。生产环境如果需要隔离脚本，应显式配置
 * OpenSandbox、AIO Sandbox 或其他远程 Runtime。</p>
 *
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
        "NOTE: The response starts with the base directory inside the configured skill runtime. Use only the runtime-backed Bash, Read, Write, Edit, Glob, and Grep tools for runtime resources.\n" +
        "When PublishFile is available, use it to turn a final runtime file into a URL before delivering the result to the user.\n" +
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


    /**
     * 创建 Skills 工具构建器。
     *
     * @return 新的构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Skills 工具构建器。
     *
     * <p>可以添加多个 Skills 根目录。所有目录中的 Skill 会作为一个批次传给
     * {@link SkillRuntime#prepare(List)}，远程 Runtime 可在这里统一上传和重写路径。</p>
     */
    public static class Builder {

        private List<Skill> skills = new ArrayList<>();

        private String toolDescriptionTemplate = TOOL_DESCRIPTION_TEMPLATE;

        private SkillRuntime runtime = new LocalSkillRuntime();

        private FilePublisher filePublisher;

        protected Builder() {

        }

        /**
         * 替换 {@code Skill} 工具的描述模板。
         *
         * <p>模板必须包含一个 {@code %s} 占位符，用于插入可用 Skill 的 XML 摘要。</p>
         *
         * @param template 工具描述模板
         * @return 当前构建器
         */
        public Builder toolDescriptionTemplate(String template) {
            this.toolDescriptionTemplate = template;
            return this;
        }

        /**
         * 配置 Skill 资源和所有配套工具的执行环境。
         *
         * @param runtime Runtime 实现，不能为 {@code null}
         * @return 当前构建器
         */
        public Builder runtime(SkillRuntime runtime) {
            if (runtime == null) {
                throw new IllegalArgumentException("runtime must not be null");
            }
            this.runtime = runtime;
            return this;
        }

        /**
         * 配置 Runtime 产物的文件发布器。
         *
         * <p>配置后，{@link #buildTools()} 会额外注册 {@code PublishFile} 工具。
         * 未配置时不注册该工具，因为 Runtime 文件路径本身不能自动转换为用户可访问 URL。</p>
         *
         * @param filePublisher 保存或上传文件并生成 URL 的应用实现
         * @return 当前构建器
         */
        public Builder filePublisher(FilePublisher filePublisher) {
            if (filePublisher == null) {
                throw new IllegalArgumentException("filePublisher must not be null");
            }
            this.filePublisher = filePublisher;
            return this;
        }


        /**
         * 添加一个本机 Skills 根目录。
         *
         * <p>目录会被递归扫描。对于远程 Runtime，这里的文件会在构建工具时上传到
         * 远端；目录字符串本身不会直接暴露给模型。</p>
         *
         * @param skillsRootDirectory 包含一个或多个 {@code SKILL.md} 的根目录
         * @return 当前构建器
         */
        public Builder addSkillsDirectory(String skillsRootDirectory) {
            this.addSkillsDirectories(Collections.singletonList(skillsRootDirectory));
            return this;
        }

        /**
         * 批量添加多个本机 Skills 根目录。
         *
         * @param skillsRootDirectories Skills 根目录列表
         * @return 当前构建器
         */
        public Builder addSkillsDirectories(List<String> skillsRootDirectories) {
            this.skills.addAll(Skills.loadDirectories(skillsRootDirectories));
            return this;
        }

        /**
         * 只构建用于发现和加载 Skill 指令的 {@code Skill} 工具。
         *
         * <p>该方法会立即调用 Runtime 的批量 {@code prepare}。如果 Skill 需要上传，
         * 上传发生在构建阶段。单独使用本方法时，调用方必须自行注册与同一 Runtime
         * 绑定的执行和文件工具。</p>
         *
         * @return Skill 加载工具
         */
        public Tool build() {
            List<Skill> preparedSkills = runtime.prepare(
                Collections.unmodifiableList(new ArrayList<>(this.skills)));
            if (preparedSkills == null || preparedSkills.size() != this.skills.size()) {
                throw new IllegalStateException("SkillRuntime.prepare must return one runtime skill per configured skill");
            }
            for (Skill skill : preparedSkills) {
                if (skill == null) {
                    throw new IllegalStateException("SkillRuntime.prepare must not return null skills");
                }
            }
            final List<Skill> runtimeSkills = Collections.unmodifiableList(new ArrayList<>(preparedSkills));
            String skillsXml = runtimeSkills.stream().map(Skill::toXml).collect(Collectors.joining("\n"));
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
                    for (Skill s : runtimeSkills) {
                        if (s.name().equals(command)) {
                            skill = s;
                            break;
                        }
                    }

                    if (skill != null) {
                        return String.format("Runtime: %s\nBase directory for this skill: %s\n\n%s",
                            runtime.getName(), skill.getBasePath(), skill.getContent());
                    }

                    return "Skill not found: " + command;
                }).build();
        }

        /**
         * 构建完整的 Runtime 工具集合。
         *
         * <p>返回的工具包括 {@code Skill}、{@code Bash}、{@code Read}、{@code Write}、
         * {@code Edit}、{@code Glob} 和 {@code Grep}。配置 FilePublisher 后还会包含
         * {@code PublishFile}。这些工具共享同一个 Runtime。
         * 使用远程 Runtime 时，应注册本方法的完整返回值，不要再混入指向宿主机的
         * Commons 文件或 Shell 工具，否则会破坏执行隔离。</p>
         *
         * @return 可以直接注册到 Prompt 的完整工具列表
         */
        public List<Tool> buildTools() {
            List<Tool> tools = new ArrayList<>();
            tools.add(build());
            tools.addAll(ToolScanner.scan(new SkillRuntimeShellTools(runtime)));
            tools.addAll(ToolScanner.scan(new SkillRuntimeFileTools(runtime)));
            tools.addAll(ToolScanner.scan(new SkillRuntimeSearchTools(runtime)));
            if (filePublisher != null) {
                tools.addAll(ToolScanner.scan(new SkillRuntimeFilePublishTools(runtime, filePublisher)));
            }
            return tools;
        }
    }
}

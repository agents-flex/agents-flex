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
package com.agentsflex.skill.local;

import com.agentsflex.skill.Skill;
import com.agentsflex.skill.runtime.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 直接在宿主机执行 Skill 的本地 Runtime。
 *
 * <p>该实现主要用于开发、调试和可信脚本。它不会创建容器，也不会提供额外权限隔离：
 * Skill 中的命令拥有启动当前 Java 进程用户所拥有的文件和网络权限。处理不可信 Skill
 * 或用户输入时，应改用 OpenSandbox、AIO Sandbox 等隔离 Runtime。</p>
 *
 * <p>Unix-like 系统使用 {@code /bin/bash -c}，Windows 使用 {@code cmd.exe /c}。
 * 标准输出和标准错误由独立线程消费，以避免子进程因管道缓冲区写满而阻塞。</p>
 *
 * <p>配置会话 ID 后，Runtime 会先把 Skill 复制到会话目录的 {@code skills} 子目录，改写
 * {@code basePath}，再在副本中执行 bootstrap，避免运行时生成的文件污染原始 Skill 目录。</p>
 */
public class LocalSkillRuntime implements SkillRuntime {

    private final SkillRuntimeWorkspace workspace;
    private final SkillRuntimeFileSystem fileSystem;
    private final Map<String, String> environment = new LinkedHashMap<>();
    private final Map<String, String> preparedSkills = new HashMap<>();

    /** 创建不启用会话目录的 Local Runtime，保持原有行为兼容。 */
    public LocalSkillRuntime() {
        this.workspace = null;
        this.fileSystem = new LocalSkillRuntimeFileSystem();
    }

    protected LocalSkillRuntime(Builder builder) {
        this.workspace = builder.conversationId == null ? null
            : SkillRuntimeWorkspace.create(builder.conversationsRoot, builder.conversationId);
        SkillRuntimeFileSystem localFileSystem = new LocalSkillRuntimeFileSystem();
        this.fileSystem = workspace == null ? localFileSystem : workspace.scopeFileSystem(localFileSystem);
        if (workspace != null) {
            try {
                Files.createDirectories(Paths.get(workspace.getRoot()));
            } catch (IOException e) {
                throw new SkillRuntimeException("Failed to create local conversation workspace", e);
            }
        }
    }

    /** @return Local Runtime 构建器 */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String getName() {
        return "local";
    }

    @Override
    public synchronized List<Skill> prepare(SkillPreparationRequest request) {
        for (Skill skill : request.getSkills()) {
            SkillRuntimeConfig config = request.getRuntimeConfig(skill);
            if (config != null) {
                environment.putAll(config.getEnvironment());
            }
        }

        List<Skill> runtimeSkills = new ArrayList<>(request.getSkills().size());
        for (Skill skill : request.getSkills()) {
            String existing = preparedSkills.get(skill.getBasePath());
            if (existing != null) {
                runtimeSkills.add(runtimeSkill(skill, existing));
                continue;
            }

            String runtimeBase = workspace == null ? skill.getBasePath() : copySkill(skill);
            Skill runtimeSkill = runtimeSkill(skill, runtimeBase);
            SkillRuntimeBootstrap.run(this, runtimeSkill, request.getRuntimeConfig(skill));
            preparedSkills.put(skill.getBasePath(), runtimeBase);
            runtimeSkills.add(runtimeSkill);
        }
        return runtimeSkills;
    }

    @Override
    public String getDefaultWorkingDirectory() {
        return workspace == null
            ? new File("").getAbsoluteFile().toPath().normalize().toString()
            : workspace.getRoot();
    }

    @Override
    public SkillRuntimeFileSystem getFileSystem() {
        return fileSystem;
    }

    @Override
    public SkillExecutionResult execute(SkillExecutionRequest request) {
        SkillExecutionRequest scopedRequest = workspace == null ? request : workspace.scopeExecution(request);
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(shellCommand(scopedRequest.getCommand()));
            builder.redirectErrorStream(false);
            builder.environment().putAll(effectiveEnvironment(scopedRequest));
            if (scopedRequest.getWorkingDirectory() != null
                && !scopedRequest.getWorkingDirectory().trim().isEmpty()) {
                builder.directory(new File(scopedRequest.getWorkingDirectory()));
            }

            process = builder.start();
            // stdout 和 stderr 必须并行读取，否则任一管道写满都可能使子进程永久阻塞。
            StreamCollector stdout = new StreamCollector(process.getInputStream());
            StreamCollector stderr = new StreamCollector(process.getErrorStream());
            stdout.start();
            stderr.start();

            boolean completed = process.waitFor(scopedRequest.getTimeoutMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                terminate(process);
            }
            stdout.join(1000);
            stderr.join(1000);
            return new SkillExecutionResult(completed ? process.exitValue() : -1,
                stdout.content(), stderr.content(), !completed);
        } catch (IOException e) {
            throw new SkillRuntimeException("Failed to execute local command", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                terminate(process);
            }
            throw new SkillRuntimeException("Local command execution was interrupted", e);
        }
    }

    @Override
    public synchronized void close() {
        environment.clear();
        preparedSkills.clear();
    }

    private synchronized Map<String, String> effectiveEnvironment(SkillExecutionRequest request) {
        Map<String, String> effective = new LinkedHashMap<>(environment);
        effective.putAll(request.getEnvironment());
        return effective;
    }

    private String copySkill(Skill skill) {
        Path source = Paths.get(skill.getBasePath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(source)) {
            throw new SkillRuntimeException("Local Runtime can only prepare file-system skills: "
                + skill.getBasePath());
        }
        Path target = Paths.get(workspace.getRoot(), "skills", safeName(skill.name()) + "-"
            + Integer.toHexString(source.toString().hashCode())).normalize();
        try {
            SkillRuntimeFiles.copySkillDirectory(source, target);
            return target.toString();
        } catch (IOException e) {
            throw new SkillRuntimeException("Failed to copy skill into local conversation workspace: "
                + skill.name(), e);
        }
    }

    private static Skill runtimeSkill(Skill skill, String basePath) {
        return new Skill(basePath, skill.getFrontMatter(), skill.getContent());
    }

    private static String safeName(String name) {
        String safe = name == null ? "skill" : name.replaceAll("[^a-zA-Z0-9._-]", "-");
        return safe.isEmpty() ? "skill" : safe;
    }

    private static String[] shellCommand(String command) {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            return new String[]{"cmd.exe", "/c", command};
        }
        return new String[]{"/bin/bash", "-c", command};
    }

    private static void terminate(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    /** Local Runtime 构建器。 */
    public static class Builder {

        private String conversationsRoot = Paths.get("target", "skills-runtime", "conversations")
            .toAbsolutePath().normalize().toString();
        private String conversationId;

        /** @param conversationId 用作会话目录名的稳定 ID */
        public Builder conversationId(String conversationId) {
            SkillRuntimeWorkspace.validateConversationId(conversationId);
            this.conversationId = conversationId;
            return this;
        }

        /** @param conversationsRoot 本机会话工作目录的绝对父路径 */
        public Builder conversationsRoot(String conversationsRoot) {
            SkillRuntimeWorkspace.create(conversationsRoot, "validation");
            this.conversationsRoot = conversationsRoot;
            return this;
        }

        /** @return 配置完成的 Local Runtime */
        public LocalSkillRuntime build() {
            return new LocalSkillRuntime(this);
        }
    }

    private static class StreamCollector extends Thread {

        private final InputStream stream;
        private final StringBuilder content = new StringBuilder();

        private StreamCollector(InputStream stream) {
            this.stream = stream;
            setDaemon(true);
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append('\n');
                }
            } catch (IOException ignored) {
                // 超时终止进程时流可能被异步关闭，此时已有输出仍然可以返回。
            }
        }

        private String content() {
            return content.toString();
        }
    }
}

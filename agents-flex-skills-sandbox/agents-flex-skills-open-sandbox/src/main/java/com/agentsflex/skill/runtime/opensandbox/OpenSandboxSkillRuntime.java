/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.skill.runtime.opensandbox;

import com.agentsflex.skill.Skill;
import com.agentsflex.skill.runtime.SkillExecutionRequest;
import com.agentsflex.skill.runtime.SkillExecutionResult;
import com.agentsflex.skill.runtime.SkillRuntime;
import com.agentsflex.skill.runtime.SkillRuntimeException;
import com.agentsflex.skill.runtime.SkillRuntimeFileSystem;
import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.OutputMessage;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunCommandRequest;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.NetworkPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 在独立 OpenSandbox 实例中执行 Skills 的 Runtime。
 *
 * <p>Runtime 首次准备 Skill、执行命令或访问文件时按需创建 Sandbox。每个本机 Skill
 * 目录会上传到 {@code remoteRoot/skill-name-hash}，返回给模型的 Skill 根路径也会改写为
 * 该远程路径。相同本机目录在同一个 Runtime 生命周期内只上传一次。</p>
 *
 * <p>本类负责 Sandbox 生命周期、命令执行和路径映射；文件操作与上传分别由
 * {@link OpenSandboxFileSystem} 和 {@link OpenSandboxSkillUploader} 实现。调用
 * {@link #close()} 会先 kill 再 close 本次创建的 Sandbox，因此不要在多个互不相关的
 * 长生命周期会话之间共享同一实例。</p>
 */
public class OpenSandboxSkillRuntime implements SkillRuntime {

    private final ConnectionConfig connectionConfig;
    private final String image;
    private final Duration sandboxTimeout;
    private final Duration readyTimeout;
    private final String remoteRoot;
    private final Map<String, String> resources;
    private final Map<String, String> environment;
    private final NetworkPolicy networkPolicy;
    private final Map<String, String> preparedSkills = new HashMap<>();
    private final SkillRuntimeFileSystem fileSystem;
    private final OpenSandboxSkillUploader skillUploader;

    private Sandbox sandbox;

    protected OpenSandboxSkillRuntime(Builder builder) {
        this.connectionConfig = builder.connectionConfig;
        this.image = builder.image;
        this.sandboxTimeout = builder.sandboxTimeout;
        this.readyTimeout = builder.readyTimeout;
        this.remoteRoot = normalizeRemoteRoot(builder.remoteRoot);
        this.resources = new LinkedHashMap<>(builder.resources);
        this.environment = new LinkedHashMap<>(builder.environment);
        this.networkPolicy = builder.networkPolicy;
        this.fileSystem = new OpenSandboxFileSystem(this::sandbox);
        this.skillUploader = new OpenSandboxSkillUploader(this::sandbox);
    }

    /**
     * 创建 OpenSandbox Runtime 构建器。
     *
     * @return 新构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String getName() {
        return "open-sandbox";
    }

    @Override
    public synchronized List<Skill> prepare(List<Skill> skills) {
        // 保留输入顺序，并为每个 Skill 创建指向沙箱目录的新对象。
        List<Skill> runtimeSkills = new ArrayList<>(skills.size());
        for (Skill skill : skills) {
            runtimeSkills.add(new Skill(prepareSkill(skill), skill.getFrontMatter(), skill.getContent()));
        }
        return runtimeSkills;
    }

    @Override
    public String getDefaultWorkingDirectory() {
        return remoteRoot;
    }

    @Override
    public SkillRuntimeFileSystem getFileSystem() {
        return fileSystem;
    }

    @Override
    public synchronized SkillExecutionResult execute(SkillExecutionRequest request) {
        RunCommandRequest command = RunCommandRequest.builder()
            .command(request.getCommand())
            .workingDirectory(request.getWorkingDirectory() == null ? remoteRoot : request.getWorkingDirectory())
            .timeout(Duration.ofMillis(request.getTimeoutMillis()))
            .envs(request.getEnvironment())
            .build();
        try {
            Execution execution = sandbox().commands().run(command);
            int exitCode = execution.getExitCode() == null ? -1 : execution.getExitCode();
            return new SkillExecutionResult(exitCode,
                messages(execution.getLogs() == null ? null : execution.getLogs().getStdout()),
                messages(execution.getLogs() == null ? null : execution.getLogs().getStderr()),
                false);
        } catch (RuntimeException e) {
            throw new SkillRuntimeException("OpenSandbox command execution failed", e);
        }
    }

    @Override
    public synchronized void close() {
        if (sandbox == null) {
            return;
        }
        try {
            // kill 负责销毁远端实例，close 负责释放 SDK 客户端侧资源。
            sandbox.kill();
        } finally {
            try {
                sandbox.close();
            } finally {
                sandbox = null;
                preparedSkills.clear();
            }
        }
    }

    private String prepareSkill(Skill skill) {
        String localBasePath = skill.getBasePath();
        String existing = preparedSkills.get(localBasePath);
        if (existing != null) {
            return existing;
        }

        Path source = Paths.get(localBasePath).toAbsolutePath().normalize();
        if (!Files.isDirectory(source)) {
            throw new SkillRuntimeException("OpenSandbox can only prepare file-system skills: " + localBasePath);
        }

        String remoteBase = remoteRoot + "/" + safeName(skill.name()) + "-"
            + Integer.toHexString(source.toString().hashCode());
        try {
            skillUploader.upload(source, remoteBase);
        } catch (IOException e) {
            throw new SkillRuntimeException("Failed to upload skill to OpenSandbox: " + skill.name(), e);
        }
        preparedSkills.put(localBasePath, remoteBase);
        return remoteBase;
    }

    private synchronized Sandbox sandbox() {
        if (sandbox == null) {
            // 延迟创建，避免只构造配置但未执行 Skill 时产生远端资源和费用。
            Sandbox.Builder builder = Sandbox.builder()
                .connectionConfig(connectionConfig)
                .image(image)
                .timeout(sandboxTimeout)
                .readyTimeout(readyTimeout)
                .resource(resources)
                .env(environment);
            if (networkPolicy != null) {
                builder.networkPolicy(networkPolicy);
            }
            sandbox = builder.build();
        }
        return sandbox;
    }

    private static String messages(List<OutputMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (OutputMessage message : messages) {
            result.append(message.getText());
            if (!message.getText().endsWith("\n")) {
                result.append('\n');
            }
        }
        return result.toString();
    }

    private static String safeName(String name) {
        String safe = name == null ? "skill" : name.replaceAll("[^a-zA-Z0-9._-]", "-");
        return safe.isEmpty() ? "skill" : safe;
    }

    private static String normalizeRemoteRoot(String root) {
        if (root == null || !root.startsWith("/")) {
            throw new IllegalArgumentException("remoteRoot must be an absolute non-root sandbox path");
        }
        String normalized = Paths.get(root).normalize().toString().replace('\\', '/');
        if ("/".equals(normalized)) {
            throw new IllegalArgumentException("remoteRoot must be an absolute non-root sandbox path");
        }
        return normalized;
    }

    /** OpenSandbox Runtime 构建器。 */
    public static class Builder {

        private ConnectionConfig connectionConfig;
        private String image = "python:3.11";
        private Duration sandboxTimeout = Duration.ofMinutes(10);
        private Duration readyTimeout = Duration.ofSeconds(30);
        private String remoteRoot = "/workspace/skills";
        private Map<String, String> resources = new LinkedHashMap<>();
        private Map<String, String> environment = new LinkedHashMap<>();
        private NetworkPolicy networkPolicy;

        /** @param connectionConfig OpenSandbox SDK 连接地址和 API Key */
        public Builder connectionConfig(ConnectionConfig connectionConfig) {
            this.connectionConfig = connectionConfig;
            return this;
        }

        /**
         * 使用 OpenSandbox SDK Builder 配置连接。
         *
         * @param configurer ConnectionConfig Builder 配置函数
         * @return 当前构建器
         */
        public Builder connectionConfig(Consumer<ConnectionConfig.Builder> configurer) {
            if (configurer == null) {
                throw new IllegalArgumentException("connection configurer must not be null");
            }
            ConnectionConfig.Builder builder = ConnectionConfig.builder();
            configurer.accept(builder);
            this.connectionConfig = builder.build();
            return this;
        }

        /** @param image Sandbox 使用的容器镜像，例如 {@code python:3.11} */
        public Builder image(String image) {
            this.image = image;
            return this;
        }

        /** @param sandboxTimeout Sandbox 最长存活时间 */
        public Builder sandboxTimeout(Duration sandboxTimeout) {
            this.sandboxTimeout = sandboxTimeout;
            return this;
        }

        /** @param readyTimeout 等待 Sandbox 就绪的最长时间 */
        public Builder readyTimeout(Duration readyTimeout) {
            this.readyTimeout = readyTimeout;
            return this;
        }

        /** @param remoteRoot Skill 上传根目录，必须是非根绝对路径 */
        public Builder remoteRoot(String remoteRoot) {
            this.remoteRoot = remoteRoot;
            return this;
        }

        /** @param resources 透传给 OpenSandbox 的 CPU、内存等资源配置 */
        public Builder resources(Map<String, String> resources) {
            this.resources = resources == null ? Collections.emptyMap() : resources;
            return this;
        }

        /** @param environment 创建 Sandbox 时注入的环境变量 */
        public Builder environment(Map<String, String> environment) {
            this.environment = environment == null ? Collections.emptyMap() : environment;
            return this;
        }

        /** @param networkPolicy Sandbox 出站网络策略 */
        public Builder networkPolicy(NetworkPolicy networkPolicy) {
            this.networkPolicy = networkPolicy;
            return this;
        }

        /**
         * 校验必要参数并创建 Runtime。
         *
         * @return 尚未创建远端 Sandbox 的 Runtime
         */
        public OpenSandboxSkillRuntime build() {
            if (connectionConfig == null) {
                throw new IllegalStateException("connectionConfig must be configured");
            }
            if (image == null || image.trim().isEmpty()) {
                throw new IllegalStateException("image must not be empty");
            }
            if (sandboxTimeout == null || readyTimeout == null) {
                throw new IllegalStateException("sandboxTimeout and readyTimeout must not be null");
            }
            return new OpenSandboxSkillRuntime(this);
        }
    }
}

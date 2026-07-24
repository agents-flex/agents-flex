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
package com.agentsflex.skill.runtime.opensandbox;

import com.agentsflex.skill.Skill;
import com.agentsflex.skill.runtime.SkillExecutionRequest;
import com.agentsflex.skill.runtime.SkillExecutionResult;
import com.agentsflex.skill.runtime.SkillFileInfo;
import com.agentsflex.skill.runtime.SkillPreparationRequest;
import com.agentsflex.skill.runtime.SkillRuntime;
import com.agentsflex.skill.runtime.SkillRuntimeBootstrap;
import com.agentsflex.skill.runtime.SkillRuntimeConfig;
import com.agentsflex.skill.runtime.SkillRuntimeException;
import com.agentsflex.skill.runtime.SkillRuntimeFileSystem;
import com.agentsflex.skill.runtime.SkillRuntimeWorkspace;
import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.config.ConnectionConfig;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.Execution;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.OutputMessage;
import com.alibaba.opensandbox.sandbox.domain.models.execd.executions.RunCommandRequest;
import com.alibaba.opensandbox.sandbox.domain.models.sandboxes.NetworkPolicy;

import java.io.IOException;
import java.io.InputStream;
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
 * 目录会上传到 {@code remoteRoot/skill-name-hash}；配置会话 ID 后则上传到对应会话目录的
 * {@code skills} 子目录。返回给模型的 Skill 根路径会改写为该远程路径，相同本机目录在同一个
 * Runtime 生命周期内只上传一次。</p>
 *
 * <p>本类负责 Sandbox 生命周期、命令执行和路径映射；文件操作与上传分别由
 * {@link OpenSandboxFileSystem} 和 {@link OpenSandboxSkillUploader} 实现。调用
 * 未配置会话 ID 时，{@link #close()} 会先 kill 再 close 本次创建的 Sandbox。配置会话 ID 后，
 * Runtime 会通过 {@link OpenSandboxConversationStore} 持久化 sandboxId。同一 Store 和会话键会复用同一个
 * 远端 Sandbox，普通 close 不再销毁远端实例；会话结束时应调用
 * {@link #destroyConversationSandbox()}。</p>
 */
public class OpenSandboxSkillRuntime implements SkillRuntime {

    private final ConnectionConfig connectionConfig;
    private final String image;
    private final Duration sandboxTimeout;
    private final Duration readyTimeout;
    private final String remoteRoot;
    private final SkillRuntimeWorkspace workspace;
    private final String workingDirectory;
    private final Map<String, String> resources;
    private final Map<String, String> baseEnvironment;
    private final Map<String, String> environment = new LinkedHashMap<>();
    private final NetworkPolicy networkPolicy;
    private final OpenSandboxConversationStore conversationStore;
    private final OpenSandboxConversationKey conversationKey;
    private final Map<String, String> preparedSkills = new HashMap<>();
    private final SkillRuntimeFileSystem fileSystem;
    private final OpenSandboxSkillUploader skillUploader;

    private Sandbox sandbox;
    private String sandboxId;
    private boolean workspaceReady;

    protected OpenSandboxSkillRuntime(Builder builder) {
        this.connectionConfig = builder.connectionConfig;
        this.image = builder.image;
        this.sandboxTimeout = builder.sandboxTimeout;
        this.readyTimeout = builder.readyTimeout;
        this.remoteRoot = normalizeRemoteRoot(builder.remoteRoot);
        this.workspace = builder.conversationId == null ? null
            : SkillRuntimeWorkspace.create(builder.conversationsRoot, builder.conversationId);
        this.workingDirectory = workspace == null ? remoteRoot : workspace.getRoot();
        this.resources = new LinkedHashMap<>(builder.resources);
        this.baseEnvironment = new LinkedHashMap<>(builder.environment);
        this.networkPolicy = builder.networkPolicy;
        this.conversationStore = workspace == null ? null : builder.conversationStore;
        this.conversationKey = workspace == null ? null
            : OpenSandboxConversationKey.of(serviceKey(connectionConfig), builder.conversationId);
        SkillRuntimeFileSystem sandboxFileSystem = new OpenSandboxFileSystem(this::sandbox);
        SkillRuntimeFileSystem conversationFileSystem = conversationKey == null
            ? sandboxFileSystem : conversationFileSystem(sandboxFileSystem);
        this.fileSystem = workspace == null
            ? conversationFileSystem : workspace.scopeFileSystem(conversationFileSystem);
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
    public synchronized List<Skill> prepare(SkillPreparationRequest request) {
        for (Skill skill : request.getSkills()) {
            SkillRuntimeConfig config = request.getRuntimeConfig(skill);
            if (config != null) {
                environment.putAll(config.getEnvironment());
            }
        }

        if (conversationKey != null) {
            loadConversationRecord();
        }
        Throwable operationFailure = null;
        try {
            ensureWorkspace();
            // 保留输入顺序，并为每个 Skill 创建指向沙箱目录的新对象。
            List<Skill> runtimeSkills = new ArrayList<>(request.getSkills().size());
            for (Skill skill : request.getSkills()) {
                String existing = preparedSkills.get(skill.getBasePath());
                if (existing != null) {
                    runtimeSkills.add(runtimeSkill(skill, existing));
                    continue;
                }

                String remoteBase = uploadSkill(skill);
                Skill runtimeSkill = runtimeSkill(skill, remoteBase);
                SkillRuntimeBootstrap.run(this, runtimeSkill, request.getRuntimeConfig(skill));
                preparedSkills.put(skill.getBasePath(), remoteBase);
                runtimeSkills.add(runtimeSkill);
            }
            return runtimeSkills;
        } catch (RuntimeException e) {
            operationFailure = e;
            throw e;
        } catch (Error e) {
            operationFailure = e;
            throw e;
        } finally {
            persistConversationRecord(operationFailure);
        }
    }

    @Override
    public String getDefaultWorkingDirectory() {
        return workingDirectory;
    }

    @Override
    public SkillRuntimeFileSystem getFileSystem() {
        return fileSystem;
    }

    @Override
    public synchronized SkillExecutionResult execute(SkillExecutionRequest request) {
        SkillExecutionRequest scopedRequest = workspace == null ? request : workspace.scopeExecution(request);
        if (conversationKey != null) {
            loadConversationRecord();
        }
        Throwable operationFailure = null;
        try {
            ensureWorkspace();
            RunCommandRequest command = RunCommandRequest.builder()
                .command(scopedRequest.getCommand())
                .workingDirectory(scopedRequest.getWorkingDirectory() == null
                    ? workingDirectory : scopedRequest.getWorkingDirectory())
                .timeout(Duration.ofMillis(scopedRequest.getTimeoutMillis()))
                .envs(effectiveEnvironment(scopedRequest))
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
        } catch (RuntimeException e) {
            operationFailure = e;
            throw e;
        } catch (Error e) {
            operationFailure = e;
            throw e;
        } finally {
            persistConversationRecord(operationFailure);
        }
    }

    @Override
    public synchronized void close() {
        environment.clear();
        if (conversationKey != null) {
            if (sandbox != null) {
                sandbox.close();
                sandbox = null;
            }
            return;
        }
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
                workspaceReady = false;
            }
        }
    }

    /**
     * 显式销毁当前 conversationId 对应的共享 Sandbox。
     *
     * <p>会先删除 Store 记录，再销毁远端 Sandbox。后续使用同一 conversationId 时会创建新的 Sandbox。</p>
     */
    public void destroyConversationSandbox() {
        if (conversationKey == null) {
            throw new IllegalStateException("conversationId must be configured to destroy a shared Sandbox");
        }
        OpenSandboxConversationRecord removed = conversationStore.delete(conversationKey);
        String storedSandboxId = removed == null ? sandboxId : removed.getSandboxId();
        Sandbox target = sandbox;
        if (target != null && storedSandboxId != null && !storedSandboxId.equals(sandboxId)) {
            target.close();
            target = null;
        }
        sandbox = null;
        sandboxId = null;
        preparedSkills.clear();
        workspaceReady = false;
        if (target == null && storedSandboxId != null) {
            target = connectSandbox(storedSandboxId, true);
        }
        if (target != null) {
            try {
                target.kill();
            } finally {
                target.close();
            }
        }
    }

    private String uploadSkill(Skill skill) {
        String localBasePath = skill.getBasePath();
        Path source = Paths.get(localBasePath).toAbsolutePath().normalize();
        if (!Files.isDirectory(source)) {
            throw new SkillRuntimeException("OpenSandbox can only prepare file-system skills: " + localBasePath);
        }

        String remoteBase = skillRoot() + "/" + safeName(skill.name()) + "-"
            + Integer.toHexString(source.toString().hashCode());
        try {
            skillUploader.upload(source, remoteBase);
        } catch (IOException e) {
            throw new SkillRuntimeException("Failed to upload skill to OpenSandbox: " + skill.name(), e);
        }
        return remoteBase;
    }

    private Map<String, String> effectiveEnvironment(SkillExecutionRequest request) {
        Map<String, String> effective = configuredEnvironment();
        effective.putAll(request.getEnvironment());
        return effective;
    }

    private String skillRoot() {
        return workspace == null ? remoteRoot : workspace.getRoot() + "/skills";
    }

    private void ensureWorkspace() {
        if (workspace == null) {
            return;
        }
        synchronized (this) {
            if (conversationKey != null && sandbox == null) {
                loadConversationRecord();
            }
            if (isWorkspaceReady()) {
                return;
            }
            RunCommandRequest command = RunCommandRequest.builder()
                .command("mkdir -p " + shellQuote(workspace.getRoot()))
                .workingDirectory("/")
                .timeout(Duration.ofSeconds(30))
                .envs(configuredEnvironment())
                .build();
            try {
                Execution execution = sandbox().commands().run(command);
                if (execution.getExitCode() == null || execution.getExitCode() != 0) {
                    throw new SkillRuntimeException("OpenSandbox failed to create conversation workspace");
                }
                setWorkspaceReady();
            } catch (SkillRuntimeException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new SkillRuntimeException("OpenSandbox failed to create conversation workspace", e);
            }
        }
    }

    private Map<String, String> configuredEnvironment() {
        Map<String, String> configured = new LinkedHashMap<>(baseEnvironment);
        configured.putAll(environment);
        return configured;
    }

    private static Skill runtimeSkill(Skill skill, String basePath) {
        return new Skill(basePath, skill.getFrontMatter(), skill.getContent());
    }

    private synchronized Sandbox sandbox() {
        if (sandbox != null) {
            return sandbox;
        }
        if (conversationKey == null) {
            sandbox = createSandbox();
            return sandbox;
        }
        OpenSandboxConversationRecord record = conversationStore.get(conversationKey);
        if (record != null && record.getSandboxId() != null) {
            applyConversationRecord(record);
            sandbox = connectSandbox(record.getSandboxId(), false);
            sandbox.renew(sandboxTimeout);
            return sandbox;
        }

        Sandbox candidate = createSandbox();
        OpenSandboxConversationRecord candidateRecord = new OpenSandboxConversationRecord(
            conversationKey, workspace.getRoot(), candidate.getId(), false,
            Collections.<String, String>emptyMap());
        boolean created;
        try {
            created = conversationStore.create(candidateRecord);
        } catch (RuntimeException e) {
            destroyCandidate(candidate, e);
            throw e;
        }
        if (created) {
            applyConversationRecord(candidateRecord);
            sandbox = candidate;
            sandbox.renew(sandboxTimeout);
            return sandbox;
        }

        destroyCandidate(candidate, null);
        record = conversationStore.get(conversationKey);
        if (record == null || record.getSandboxId() == null) {
            throw new SkillRuntimeException(
                "OpenSandbox conversation was created without a reusable sandboxId");
        }
        applyConversationRecord(record);
        sandbox = connectSandbox(record.getSandboxId(), false);
        sandbox.renew(sandboxTimeout);
        return sandbox;
    }

    private Sandbox createSandbox() {
        // 延迟创建，避免只构造配置但未执行 Skill 时产生远端资源和费用。
        Sandbox.Builder builder = Sandbox.builder()
            .connectionConfig(connectionConfig)
            .image(image)
            .timeout(sandboxTimeout)
            .readyTimeout(readyTimeout)
            .resource(resources)
            .env(configuredEnvironment());
        if (networkPolicy != null) {
            builder.networkPolicy(networkPolicy);
        }
        if (conversationKey != null) {
            builder.metadata("agentsflex.conversation-id", conversationKey.getConversationId());
            builder.metadata("agentsflex.conversation-key", conversationKey.getStorageKey());
        }
        return builder.build();
    }

    private Sandbox connectSandbox(String sandboxId, boolean skipHealthCheck) {
        return Sandbox.connector()
            .sandboxId(sandboxId)
            .connectionConfig(connectionConfig)
            .connectTimeout(readyTimeout)
            .skipHealthCheck(skipHealthCheck)
            .connect();
    }

    private synchronized void loadConversationRecord() {
        OpenSandboxConversationRecord record = conversationStore.get(conversationKey);
        if (record != null) {
            applyConversationRecord(record);
        } else if (sandboxId != null) {
            if (sandbox != null) {
                sandbox.close();
                sandbox = null;
            }
            sandboxId = null;
            workspaceReady = false;
            preparedSkills.clear();
        }
    }

    private void applyConversationRecord(OpenSandboxConversationRecord record) {
        if (!workspace.getRoot().equals(record.getWorkspaceRoot())) {
            throw new IllegalStateException("The same conversation key must use the same conversationsRoot");
        }
        String storedSandboxId = record.getSandboxId();
        if (sandboxId != null && !sandboxId.equals(storedSandboxId)) {
            if (sandbox != null) {
                sandbox.close();
                sandbox = null;
            }
        }
        sandboxId = storedSandboxId;
        workspaceReady = record.isWorkspaceReady();
        preparedSkills.clear();
        preparedSkills.putAll(record.getPreparedSkills());
    }

    private OpenSandboxConversationRecord conversationRecord() {
        return new OpenSandboxConversationRecord(conversationKey, workspace.getRoot(), sandboxId,
            workspaceReady, preparedSkills);
    }

    private void persistConversationRecord(Throwable operationFailure) {
        if (conversationKey == null || sandboxId == null) {
            return;
        }
        try {
            conversationStore.update(conversationRecord());
        } catch (RuntimeException saveFailure) {
            if (operationFailure != null) {
                operationFailure.addSuppressed(saveFailure);
            } else {
                throw saveFailure;
            }
        }
    }

    private static void destroyCandidate(Sandbox candidate, RuntimeException originalFailure) {
        RuntimeException cleanupFailure = null;
        try {
            candidate.kill();
        } catch (RuntimeException e) {
            cleanupFailure = e;
        } finally {
            try {
                candidate.close();
            } catch (RuntimeException e) {
                if (cleanupFailure == null) {
                    cleanupFailure = e;
                } else {
                    cleanupFailure.addSuppressed(e);
                }
            }
        }
        if (cleanupFailure != null) {
            if (originalFailure != null) {
                originalFailure.addSuppressed(cleanupFailure);
            } else {
                throw cleanupFailure;
            }
        }
    }

    private SkillRuntimeFileSystem conversationFileSystem(SkillRuntimeFileSystem delegate) {
        return new SkillRuntimeFileSystem() {
            @Override
            public InputStream openInputStream(String path) {
                ensureWorkspace();
                return delegate.openInputStream(path);
            }

            @Override
            public String readText(String path, int maxBytes) {
                ensureWorkspace();
                return delegate.readText(path, maxBytes);
            }

            @Override
            public void writeText(String path, String content) {
                ensureWorkspace();
                delegate.writeText(path, content);
            }

            @Override
            public SkillFileInfo stat(String path) {
                ensureWorkspace();
                return delegate.stat(path);
            }

            @Override
            public List<SkillFileInfo> listDirectory(String path, int maxDepth, int maxResults) {
                ensureWorkspace();
                return delegate.listDirectory(path, maxDepth, maxResults);
            }

            @Override
            public List<SkillFileInfo> listFiles(String path, int maxDepth, int maxResults) {
                ensureWorkspace();
                return delegate.listFiles(path, maxDepth, maxResults);
            }
        };
    }

    private boolean isWorkspaceReady() {
        return workspaceReady;
    }

    private void setWorkspaceReady() {
        workspaceReady = true;
        if (conversationKey != null) {
            persistConversationRecord(null);
        }
    }

    private static String serviceKey(ConnectionConfig connectionConfig) {
        return OpenSandboxConversationKey.digest(connectionConfig.getBaseUrl());
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

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
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

    /**
     * OpenSandbox Runtime 构建器。
     */
    public static class Builder {

        private ConnectionConfig connectionConfig;
        private String image = "python:3.11";
        private Duration sandboxTimeout = Duration.ofMinutes(10);
        private Duration readyTimeout = Duration.ofSeconds(30);
        private String remoteRoot = "/workspace/skills";
        private String conversationsRoot = "/workspace/conversations";
        private String conversationId;
        private OpenSandboxConversationStore conversationStore =
            InMemoryOpenSandboxConversationStore.shared();
        private Map<String, String> resources = new LinkedHashMap<>();
        private Map<String, String> environment = new LinkedHashMap<>();
        private NetworkPolicy networkPolicy;

        /**
         * @param connectionConfig OpenSandbox SDK 连接地址和 API Key
         */
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

        /**
         * @param image Sandbox 使用的容器镜像，例如 {@code python:3.11}
         */
        public Builder image(String image) {
            this.image = image;
            return this;
        }

        /**
         * @param sandboxTimeout Sandbox 最长存活时间
         */
        public Builder sandboxTimeout(Duration sandboxTimeout) {
            this.sandboxTimeout = sandboxTimeout;
            return this;
        }

        /**
         * @param readyTimeout 等待 Sandbox 就绪的最长时间
         */
        public Builder readyTimeout(Duration readyTimeout) {
            this.readyTimeout = readyTimeout;
            return this;
        }

        /**
         * @param remoteRoot Skill 上传根目录，必须是非根绝对路径
         */
        public Builder remoteRoot(String remoteRoot) {
            this.remoteRoot = remoteRoot;
            return this;
        }

        /**
         * @param conversationId 用作会话目录名的稳定 ID
         */
        public Builder conversationId(String conversationId) {
            SkillRuntimeWorkspace.validateConversationId(conversationId);
            this.conversationId = conversationId;
            return this;
        }

        /**
         * @param conversationStore 会话状态持久化实现
         */
        public Builder conversationStore(OpenSandboxConversationStore conversationStore) {
            if (conversationStore == null) {
                throw new IllegalArgumentException("conversationStore must not be null");
            }
            this.conversationStore = conversationStore;
            return this;
        }

        /**
         * @param conversationsRoot Sandbox 内会话工作目录的绝对父路径
         */
        public Builder conversationsRoot(String conversationsRoot) {
            SkillRuntimeWorkspace.create(conversationsRoot, "validation");
            this.conversationsRoot = conversationsRoot;
            return this;
        }

        /**
         * @param resources 透传给 OpenSandbox 的 CPU、内存等资源配置
         */
        public Builder resources(Map<String, String> resources) {
            this.resources = resources == null ? Collections.emptyMap() : resources;
            return this;
        }

        /**
         * @param environment 创建 Sandbox 时注入的环境变量
         */
        public Builder environment(Map<String, String> environment) {
            this.environment = environment == null ? Collections.emptyMap() : environment;
            return this;
        }

        /**
         * @param networkPolicy Sandbox 出站网络策略
         */
        public Builder networkPolicy(NetworkPolicy networkPolicy) {
            this.networkPolicy = networkPolicy;
            return this;
        }

        /**
         * 使用 OpenSandbox SDK Builder 配置 Sandbox 出站网络策略。
         *
         * @param configurer NetworkPolicy Builder 配置函数
         * @return 当前构建器
         */
        public Builder networkPolicy(Consumer<NetworkPolicy.Builder> configurer) {
            if (configurer == null) {
                throw new IllegalArgumentException("network policy configurer must not be null");
            }
            NetworkPolicy.Builder builder = NetworkPolicy.builder();
            configurer.accept(builder);
            this.networkPolicy = builder.build();
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
            if (conversationStore == null) {
                throw new IllegalStateException("conversationStore must not be null");
            }
            return new OpenSandboxSkillRuntime(this);
        }
    }
}

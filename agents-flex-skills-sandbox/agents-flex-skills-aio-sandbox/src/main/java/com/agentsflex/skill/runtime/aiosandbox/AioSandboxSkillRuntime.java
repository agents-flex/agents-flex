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
package com.agentsflex.skill.runtime.aiosandbox;

import com.agentsflex.skill.Skill;
import com.agentsflex.skill.runtime.SkillExecutionRequest;
import com.agentsflex.skill.runtime.SkillExecutionResult;
import com.agentsflex.skill.runtime.SkillPreparationRequest;
import com.agentsflex.skill.runtime.SkillRuntime;
import com.agentsflex.skill.runtime.SkillRuntimeBootstrap;
import com.agentsflex.skill.runtime.SkillRuntimeConfig;
import com.agentsflex.skill.runtime.SkillRuntimeException;
import com.agentsflex.skill.runtime.SkillRuntimeFileSystem;

import java.io.IOException;
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
 * 连接到一个已经运行的 AIO Sandbox HTTP 服务的 Skill Runtime。
 *
 * <p>与 OpenSandbox 不同，本实现不会创建或销毁容器；应用负责在外部启动 AIO 服务，
 * Runtime 只上传 Skill、调用 Shell/文件 API 并维护本地路径到远程路径的缓存。
 * {@link #close()} 只清理缓存，不会停止共享的 AIO 服务。</p>
 *
 * <p>每个本机 Skill 上传到 {@code remoteRoot/skill-name-hash}。协议访问、文件操作和上传
 * 分别由 {@link AioSandboxClient}、{@link AioSandboxFileSystem} 和
 * {@link AioSandboxSkillUploader} 承担。</p>
 */
public class AioSandboxSkillRuntime implements SkillRuntime {

    private final String remoteRoot;
    private final Map<String, String> preparedSkills = new HashMap<>();
    private final Map<String, String> environment = new LinkedHashMap<>();
    private final AioSandboxClient client;
    private final SkillRuntimeFileSystem fileSystem;
    private final AioSandboxSkillUploader skillUploader;

    protected AioSandboxSkillRuntime(Builder builder) {
        this.remoteRoot = normalizeRemoteRoot(builder.remoteRoot);
        this.client = new AioSandboxClient(builder.baseUrl, builder.bearerToken, builder.httpTimeoutMillis);
        this.fileSystem = new AioSandboxFileSystem(client);
        this.skillUploader = new AioSandboxSkillUploader(client, remoteRoot);
    }

    /** @return AIO Sandbox Runtime 构建器 */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String getName() {
        return "aio-sandbox";
    }

    @Override
    public synchronized List<Skill> prepare(SkillPreparationRequest request) {
        for (Skill skill : request.getSkills()) {
            SkillRuntimeConfig config = request.getRuntimeConfig(skill);
            if (config != null) {
                environment.putAll(config.getEnvironment());
            }
        }

        // 返回路径已转换的新 Skill，绝不修改调用方传入对象。
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
    public SkillExecutionResult execute(SkillExecutionRequest request) {
        return client.execute(withEffectiveEnvironment(request), remoteRoot);
    }

    @Override
    public synchronized void close() {
        // AIO 服务可能由多个应用共享，本 Runtime 不拥有其进程或容器生命周期。
        preparedSkills.clear();
        environment.clear();
    }

    private String uploadSkill(Skill skill) {
        String localBasePath = skill.getBasePath();
        Path source = Paths.get(localBasePath).toAbsolutePath().normalize();
        if (!Files.isDirectory(source)) {
            throw new SkillRuntimeException("AIO Sandbox can only prepare file-system skills: " + localBasePath);
        }

        String remoteBase = remoteRoot + "/" + safeName(skill.name()) + "-"
            + Integer.toHexString(source.toString().hashCode());
        try {
            skillUploader.upload(source, remoteBase);
        } catch (IOException e) {
            throw new SkillRuntimeException("Failed to upload skill to AIO Sandbox: " + skill.name(), e);
        }
        return remoteBase;
    }

    private synchronized SkillExecutionRequest withEffectiveEnvironment(SkillExecutionRequest request) {
        Map<String, String> effective = new LinkedHashMap<>(environment);
        effective.putAll(request.getEnvironment());
        return new SkillExecutionRequest(request.getCommand(), request.getWorkingDirectory(),
            request.getTimeoutMillis(), effective);
    }

    private static Skill runtimeSkill(Skill skill, String basePath) {
        return new Skill(basePath, skill.getFrontMatter(), skill.getContent());
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

    /** AIO Sandbox Runtime 构建器。 */
    public static class Builder {

        private String baseUrl = "http://localhost:8080";
        private String bearerToken;
        private String remoteRoot = "/home/gem/workspace/skills";
        private int httpTimeoutMillis = (int) TimeUnit.MINUTES.toMillis(11);

        /** @param baseUrl AIO HTTP 服务根地址 */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /** @param bearerToken 启用 JWT 鉴权时使用的 Bearer Token */
        public Builder bearerToken(String bearerToken) {
            this.bearerToken = bearerToken;
            return this;
        }

        /** @param remoteRoot Skill 上传根目录，必须是非根绝对路径 */
        public Builder remoteRoot(String remoteRoot) {
            this.remoteRoot = remoteRoot;
            return this;
        }

        /** @param httpTimeoutMillis 单次 HTTP 请求读取超时 */
        public Builder httpTimeoutMillis(int httpTimeoutMillis) {
            if (httpTimeoutMillis <= 0) {
                throw new IllegalArgumentException("httpTimeoutMillis must be greater than zero");
            }
            this.httpTimeoutMillis = httpTimeoutMillis;
            return this;
        }

        /** @return 配置完成的 AIO Runtime */
        public AioSandboxSkillRuntime build() {
            return new AioSandboxSkillRuntime(this);
        }
    }
}

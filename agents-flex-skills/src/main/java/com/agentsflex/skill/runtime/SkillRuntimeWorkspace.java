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
package com.agentsflex.skill.runtime;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 所有 Skill Runtime 共用的会话工作目录边界。
 *
 * <p>相对路径统一基于当前会话目录解析，文件读写、目录遍历和命令工作目录都只能位于该目录内。
 * 这是用于减少跨会话误操作的词法路径检查；任意 Shell 命令和符号链接仍需依赖进程、文件权限或
 * 容器提供真正的安全隔离。</p>
 */
public final class SkillRuntimeWorkspace {

    private final String root;
    private final Path rootPath;

    private SkillRuntimeWorkspace(String root) {
        this.root = normalizeAbsoluteRoot(root);
        this.rootPath = Paths.get(this.root);
    }

    /**
     * 在 {@code conversationsRoot/conversationId} 创建会话工作目录描述。
     */
    public static SkillRuntimeWorkspace create(String conversationsRoot, String conversationId) {
        validateConversationId(conversationId);
        String normalizedRoot = normalizeAbsoluteRoot(conversationsRoot);
        return new SkillRuntimeWorkspace(normalizedRoot + "/" + conversationId);
    }

    /**
     * 在会话 ID 用作目录名之前校验其格式。
     */
    public static void validateConversationId(String conversationId) {
        if (conversationId == null || !conversationId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
            || ".".equals(conversationId) || "..".equals(conversationId)) {
            throw new IllegalArgumentException("conversationId must be 1-128 characters and contain only "
                + "letters, digits, dots, underscores, or hyphens");
        }
    }

    /**
     * @return 规范化后的会话工作目录绝对路径
     */
    public String getRoot() {
        return root;
    }

    /**
     * 基于当前会话目录解析读取、列表或 stat 路径。
     */
    public String resolveReadablePath(String path) {
        return resolve(path);
    }

    /**
     * 基于当前会话目录解析写入路径，并拒绝目录外的位置。
     */
    public String resolveWritablePath(String path) {
        return resolve(path);
    }

    /**
     * 为命令请求设置默认工作目录，并校验显式工作目录是否位于当前会话中。
     */
    public SkillExecutionRequest scopeExecution(SkillExecutionRequest request) {
        String workingDirectory = request.getWorkingDirectory();
        String resolved = workingDirectory == null || workingDirectory.trim().isEmpty()
            ? root : resolve(workingDirectory);
        return new SkillExecutionRequest(request.getCommand(), resolved, request.getTimeoutMillis(),
            request.getEnvironment());
    }

    /**
     * 为 Runtime 文件系统包装统一的会话路径检查。
     */
    public SkillRuntimeFileSystem scopeFileSystem(SkillRuntimeFileSystem delegate) {
        return new WorkspaceSkillRuntimeFileSystem(delegate, this);
    }

    private String resolve(String path) {
        if (path == null || path.trim().isEmpty()) {
            throw new SkillRuntimeException("Runtime path must not be empty");
        }
        String value = path.trim().replace('\\', '/');
        Path candidate = Paths.get(value);
        Path resolved = (candidate.isAbsolute() ? candidate : rootPath.resolve(candidate)).normalize();
        String normalized = normalize(resolved);
        if (isWithin(normalized, root)) {
            return normalized;
        }
        throw new SkillRuntimeException("Path is outside the conversation workspace " + root + ": " + path);
    }

    private static boolean isWithin(String path, String root) {
        return path.equals(root) || path.startsWith(root + "/");
    }

    private static String normalizeAbsoluteRoot(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("conversationsRoot must not be empty");
        }
        Path path = Paths.get(value.trim().replace('\\', '/'));
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("conversationsRoot must be an absolute path");
        }
        String normalized = normalize(path.normalize());
        if ("/".equals(normalized)) {
            throw new IllegalArgumentException("conversationsRoot must not be the filesystem root");
        }
        return normalized;
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }
}

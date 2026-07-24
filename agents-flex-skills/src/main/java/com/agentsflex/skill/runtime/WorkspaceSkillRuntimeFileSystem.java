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

import java.io.InputStream;
import java.util.List;

/**
 * 为任意 Runtime 文件系统实现应用 {@link SkillRuntimeWorkspace} 会话边界。
 */
final class WorkspaceSkillRuntimeFileSystem implements SkillRuntimeFileSystem {

    private final SkillRuntimeFileSystem delegate;
    private final SkillRuntimeWorkspace workspace;

    WorkspaceSkillRuntimeFileSystem(SkillRuntimeFileSystem delegate, SkillRuntimeWorkspace workspace) {
        if (delegate == null || workspace == null) {
            throw new IllegalArgumentException("delegate and workspace must not be null");
        }
        this.delegate = delegate;
        this.workspace = workspace;
    }

    @Override
    public InputStream openInputStream(String path) {
        return delegate.openInputStream(workspace.resolveReadablePath(path));
    }

    @Override
    public String readText(String path, int maxBytes) {
        return delegate.readText(workspace.resolveReadablePath(path), maxBytes);
    }

    @Override
    public void writeText(String path, String content) {
        delegate.writeText(workspace.resolveWritablePath(path), content);
    }

    @Override
    public SkillFileInfo stat(String path) {
        return delegate.stat(workspace.resolveReadablePath(path));
    }

    @Override
    public List<SkillFileInfo> listDirectory(String path, int maxDepth, int maxResults) {
        return delegate.listDirectory(workspace.resolveReadablePath(path), maxDepth, maxResults);
    }

    @Override
    public List<SkillFileInfo> listFiles(String path, int maxDepth, int maxResults) {
        return delegate.listFiles(workspace.resolveReadablePath(path), maxDepth, maxResults);
    }
}

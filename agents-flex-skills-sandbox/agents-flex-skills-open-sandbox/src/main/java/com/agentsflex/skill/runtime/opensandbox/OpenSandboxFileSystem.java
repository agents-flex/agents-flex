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

import com.agentsflex.skill.runtime.SkillFileInfo;
import com.agentsflex.skill.runtime.SkillRuntimeException;
import com.agentsflex.skill.runtime.SkillRuntimeFileSystem;
import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.EntryInfo;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 基于 OpenSandbox SDK 的 Runtime 文件系统实现。
 *
 * <p>二进制下载直接使用 SDK 的 {@code readStream}，不会把整个文件缓存在 Java 堆中。
 * {@link Supplier} 允许 Runtime 延迟创建 Sandbox，并保证所有文件操作使用同一个实例。</p>
 */
public class OpenSandboxFileSystem implements SkillRuntimeFileSystem {

    private final Supplier<Sandbox> sandboxSupplier;

    /** @param sandboxSupplier 返回当前 Runtime 所属 Sandbox 的延迟供应器 */
    public OpenSandboxFileSystem(Supplier<Sandbox> sandboxSupplier) {
        this.sandboxSupplier = sandboxSupplier;
    }

    @Override
    public InputStream openInputStream(String path) {
        try {
            return sandbox().files().readStream(path);
        } catch (RuntimeException e) {
            throw new SkillRuntimeException("OpenSandbox failed to open file: " + path, e);
        }
    }

    @Override
    public String readText(String path, int maxBytes) {
        return new String(readBytes(path, maxBytes), StandardCharsets.UTF_8);
    }

    @Override
    public void writeText(String path, String content) {
        try {
            Path parent = Paths.get(path).getParent();
            if (parent != null) {
                sandbox().files().createDirectories(Collections.singletonList(WriteEntry.builder()
                    .path(parent.toString().replace('\\', '/')).mode(755).build()));
            }
            sandbox().files().writeFile(WriteEntry.builder()
                .path(path)
                .data(content == null ? "" : content)
                .mode(644)
                .encoding("UTF-8")
                .build());
        } catch (RuntimeException e) {
            throw new SkillRuntimeException("OpenSandbox failed to write file: " + path, e);
        }
    }

    @Override
    public SkillFileInfo stat(String path) {
        try {
            Map<String, EntryInfo> values = sandbox().files().readFileInfo(Collections.singletonList(path));
            EntryInfo info = values.get(path);
            if (info == null && !values.isEmpty()) {
                info = values.values().iterator().next();
            }
            return info == null ? null : toSkillFileInfo(info);
        } catch (RuntimeException e) {
            // SDK 对不存在路径也可能抛异常，统一映射为 stat 的“未找到”语义。
            return null;
        }
    }

    @Override
    public List<SkillFileInfo> listDirectory(String path, int maxDepth, int maxResults) {
        SkillFileInfo rootInfo = stat(path);
        if (rootInfo == null) {
            throw new SkillRuntimeException("Path does not exist in OpenSandbox: " + path);
        }
        if (!rootInfo.isDirectory()) {
            return Collections.singletonList(rootInfo);
        }
        try {
            List<SkillFileInfo> result = new ArrayList<>();
            for (EntryInfo info : sandbox().files().listDirectory(path, Math.max(1, maxDepth))) {
                result.add(toSkillFileInfo(info));
                if (result.size() >= maxResults) {
                    break;
                }
            }
            return result;
        } catch (RuntimeException e) {
            throw new SkillRuntimeException("OpenSandbox failed to list directory: " + path, e);
        }
    }

    @Override
    public List<SkillFileInfo> listFiles(String path, int maxDepth, int maxResults) {
        SkillFileInfo rootInfo = stat(path);
        if (rootInfo == null) {
            throw new SkillRuntimeException("Path does not exist in OpenSandbox: " + path);
        }
        if (!rootInfo.isDirectory()) {
            return Collections.singletonList(rootInfo);
        }
        try {
            List<SkillFileInfo> result = new ArrayList<>();
            for (EntryInfo info : sandbox().files().listDirectory(path, maxDepth)) {
                if ("file".equalsIgnoreCase(info.getType())) {
                    result.add(toSkillFileInfo(info));
                    if (result.size() >= maxResults) {
                        break;
                    }
                }
            }
            return result;
        } catch (RuntimeException e) {
            throw new SkillRuntimeException("OpenSandbox failed to list files: " + path, e);
        }
    }

    private Sandbox sandbox() {
        return sandboxSupplier.get();
    }

    private static SkillFileInfo toSkillFileInfo(EntryInfo info) {
        long modified = info.getModifiedAt() == null ? 0 : info.getModifiedAt().toInstant().toEpochMilli();
        return new SkillFileInfo(info.getPath(), "directory".equalsIgnoreCase(info.getType()),
            info.getSize(), modified);
    }
}

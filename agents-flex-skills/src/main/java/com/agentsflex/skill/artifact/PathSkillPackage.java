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
package com.agentsflex.skill.artifact;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** 使用本地文件作为安装输入的 Skill 包实现。 */
public class PathSkillPackage implements SkillPackage {

    private final Path path;

    public PathSkillPackage(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("path must be an existing file: " + path);
        }
        this.path = path.toAbsolutePath().normalize();
    }

    @Override
    public InputStream openStream() throws IOException {
        return Files.newInputStream(path);
    }

    @Override
    public String getFileName() {
        return path.getFileName().toString();
    }

    @Override
    public long getSize() {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new SkillArtifactStoreException("Failed to read skill package size: " + path, e);
        }
    }
}

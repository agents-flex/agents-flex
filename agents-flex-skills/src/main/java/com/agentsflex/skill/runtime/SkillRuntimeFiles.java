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

/**
 * 从宿主机上传 Skill 到远程 Runtime 时共享的文件过滤策略。
 *
 * <p>当前策略默认排除 {@code .git} 目录、{@code .env}、{@code .env.*} 和以
 * {@code credentials} 开头的文件，避免把常见密钥文件无意上传到第三方 Sandbox。
 * 这只是基础防线，不能替代调用方对 Skill 目录内容的审计。</p>
 */
public final class SkillRuntimeFiles {

    private SkillRuntimeFiles() {
    }

    /**
     * 判断文件树遍历是否应该进入某个目录。
     *
     * @param root 本次上传的 Skill 根目录
     * @param directory 待访问目录
     * @return 根目录或不包含 {@code .git} 路径段时返回 {@code true}
     */
    public static boolean shouldVisitDirectory(Path root, Path directory) {
        if (root.equals(directory)) {
            return true;
        }
        Path relative = root.relativize(directory);
        for (Path part : relative) {
            if (".git".equalsIgnoreCase(part.toString())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断普通文件是否允许上传。
     *
     * @param root 本次上传的 Skill 根目录
     * @param file 待上传文件
     * @return 文件不在 {@code .git} 中且文件名不属于默认敏感文件模式时返回 {@code true}
     */
    public static boolean shouldUploadFile(Path root, Path file) {
        Path relative = root.relativize(file);
        for (Path part : relative) {
            if (".git".equalsIgnoreCase(part.toString())) {
                return false;
            }
        }
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return !(".env".equals(name) || name.startsWith(".env.") || name.startsWith("credentials"));
    }
}

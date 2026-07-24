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

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * 从宿主机复制或上传 Skill 到 Runtime 时共享的文件处理策略。
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
     * @param root      本次上传的 Skill 根目录
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
     * 判断普通文件是否允许复制或上传。
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

    /**
     * 将本机 Skill 目录复制到会话工作目录。
     *
     * <p>复制规则与远程上传保持一致：不跟随符号链接，并跳过 {@code .git} 和常见敏感文件。
     * 目标目录中由 Skill 运行时生成、但源目录中不存在的文件会保留，以便后续对话继续使用。</p>
     *
     * @param source 本机 Skill 根目录
     * @param target 会话内的目标 Skill 根目录
     * @throws IOException 遍历、创建目录或复制文件失败
     */
    public static void copySkillDirectory(final Path source, final Path target) throws IOException {
        if (target.startsWith(source)) {
            throw new IOException("Skill 复制目标目录不能位于源目录内部: " + target);
        }
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
                if (!shouldVisitDirectory(source, directory)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile() && shouldUploadFile(source, file)) {
                    Files.copy(file, target.resolve(source.relativize(file)),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}

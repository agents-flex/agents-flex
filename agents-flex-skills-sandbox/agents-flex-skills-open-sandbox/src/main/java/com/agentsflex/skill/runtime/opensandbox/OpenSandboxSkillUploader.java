/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.skill.runtime.opensandbox;

import com.agentsflex.skill.runtime.SkillRuntimeFiles;
import com.alibaba.opensandbox.sandbox.Sandbox;
import com.alibaba.opensandbox.sandbox.domain.models.execd.filesystem.WriteEntry;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 通过 OpenSandbox 文件系统 SDK 上传本机 Skill 目录。
 *
 * <p>上传时保留目录结构和可执行权限，跳过符号链接以及
 * {@link SkillRuntimeFiles} 定义的敏感文件。文件按批次写入，避免单个请求过大。</p>
 */
public class OpenSandboxSkillUploader {

    private static final int WRITE_BATCH_SIZE = 100;

    private final Supplier<Sandbox> sandboxSupplier;

    /** @param sandboxSupplier 返回当前 Runtime 所属 Sandbox 的延迟供应器 */
    public OpenSandboxSkillUploader(Supplier<Sandbox> sandboxSupplier) {
        this.sandboxSupplier = sandboxSupplier;
    }

    /**
     * 递归上传一个 Skill 目录。
     *
     * @param source 本机 Skill 根目录
     * @param remoteBase OpenSandbox 内的目标根目录
     * @throws IOException 遍历或读取本机文件失败
     */
    public void upload(final Path source, final String remoteBase) throws IOException {
        final List<WriteEntry> directories = new ArrayList<>();
        final List<WriteEntry> files = new ArrayList<>();
        Files.walkFileTree(source, EnumSet.noneOf(java.nio.file.FileVisitOption.class), Integer.MAX_VALUE,
            new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!SkillRuntimeFiles.shouldVisitDirectory(source, dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    directories.add(WriteEntry.builder()
                        .path(remotePath(source, dir, remoteBase))
                        .mode(755)
                        .build());
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isRegularFile() && SkillRuntimeFiles.shouldUploadFile(source, file)) {
                        files.add(WriteEntry.builder()
                            .path(remotePath(source, file, remoteBase))
                            .data(Files.readAllBytes(file))
                            .mode(isExecutable(file) ? 755 : 644)
                            .build());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

        // 先创建所有目录，再分批写文件，保证文件 API 不依赖隐式创建父目录。
        sandbox().files().createDirectories(directories);
        for (int start = 0; start < files.size(); start += WRITE_BATCH_SIZE) {
            int end = Math.min(start + WRITE_BATCH_SIZE, files.size());
            sandbox().files().write(new ArrayList<>(files.subList(start, end)));
        }
    }

    private Sandbox sandbox() {
        return sandboxSupplier.get();
    }

    private static String remotePath(Path source, Path path, String remoteBase) {
        Path relative = source.relativize(path);
        if (relative.getNameCount() == 0) {
            return remoteBase;
        }
        return remoteBase + "/" + relative.toString().replace('\\', '/');
    }

    private static boolean isExecutable(Path file) {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
            return permissions.contains(PosixFilePermission.OWNER_EXECUTE)
                || permissions.contains(PosixFilePermission.GROUP_EXECUTE)
                || permissions.contains(PosixFilePermission.OTHERS_EXECUTE);
        } catch (UnsupportedOperationException | IOException e) {
            return Files.isExecutable(file);
        }
    }
}

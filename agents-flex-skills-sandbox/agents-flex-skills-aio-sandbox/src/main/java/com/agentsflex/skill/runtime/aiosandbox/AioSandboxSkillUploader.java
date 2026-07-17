/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.skill.runtime.aiosandbox;

import com.agentsflex.skill.runtime.SkillRuntimeFiles;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * 将本机 Skill 目录上传到 AIO Sandbox。
 *
 * <p>目录通过批量 {@code mkdir -p} 创建，文件以 Base64 方式调用
 * {@code /v1/file/write} 上传，最后批量恢复可执行权限。遍历过程跳过符号链接及
 * {@link SkillRuntimeFiles} 定义的敏感文件。</p>
 */
public class AioSandboxSkillUploader {

    private static final int DIRECTORY_BATCH_SIZE = 50;

    private final AioSandboxClient client;
    private final String remoteRoot;

    /**
     * @param client AIO 协议客户端
     * @param remoteRoot Runtime 默认远程根目录
     */
    public AioSandboxSkillUploader(AioSandboxClient client, String remoteRoot) {
        this.client = client;
        this.remoteRoot = remoteRoot;
    }

    /**
     * 递归上传一个 Skill 目录。
     *
     * @param source 本机 Skill 根目录
     * @param remoteBase AIO 内目标根目录
     * @throws IOException 遍历或读取本机文件失败
     */
    public void upload(final Path source, final String remoteBase) throws IOException {
        final List<String> directories = new ArrayList<>();
        final List<Path> files = new ArrayList<>();
        final List<String> executableFiles = new ArrayList<>();

        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!SkillRuntimeFiles.shouldVisitDirectory(source, dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                directories.add(remotePath(source, dir, remoteBase));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && SkillRuntimeFiles.shouldUploadFile(source, file)) {
                    files.add(file);
                    if (isExecutable(file)) {
                        executableFiles.add(remotePath(source, file, remoteBase));
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });

        // 先创建目录，避免逐文件上传时父目录不存在。
        for (int start = 0; start < directories.size(); start += DIRECTORY_BATCH_SIZE) {
            int end = Math.min(start + DIRECTORY_BATCH_SIZE, directories.size());
            StringBuilder command = new StringBuilder("mkdir -p");
            for (String directory : directories.subList(start, end)) {
                command.append(' ').append(AioSandboxFileSystem.shellQuote(directory));
            }
            AioSandboxFileSystem.requireSuccessful(client.execute(command.toString(), "/", 30000),
                "create remote directories");
        }

        for (Path file : files) {
            JSONObject payload = new JSONObject();
            payload.put("file", remotePath(source, file, remoteBase));
            payload.put("content", Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
            payload.put("encoding", "base64");
            client.post("/v1/file/write", payload, "upload skill file");
        }

        for (int start = 0; start < executableFiles.size(); start += DIRECTORY_BATCH_SIZE) {
            int end = Math.min(start + DIRECTORY_BATCH_SIZE, executableFiles.size());
            StringBuilder command = new StringBuilder("chmod 755");
            for (String file : executableFiles.subList(start, end)) {
                command.append(' ').append(AioSandboxFileSystem.shellQuote(file));
            }
            AioSandboxFileSystem.requireSuccessful(client.execute(command.toString(), remoteRoot, 30000),
                "set executable permissions");
        }
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

/*
 * Copyright 2026 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.agentsflex.skill.local;

import com.agentsflex.skill.runtime.SkillFileInfo;
import com.agentsflex.skill.runtime.SkillRuntimeException;
import com.agentsflex.skill.runtime.SkillRuntimeFileSystem;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@link LocalSkillRuntime} 专用的宿主机文件系统实现。
 *
 * <p>传入路径会转换为规范化绝对路径，但不会限制在某个工作目录中。其访问权限与当前
 * Java 进程完全相同，因此它不是安全沙箱。</p>
 */
class LocalSkillRuntimeFileSystem implements SkillRuntimeFileSystem {

    @Override
    public InputStream openInputStream(String path) {
        Path file = Paths.get(path).toAbsolutePath().normalize();
        try {
            if (!Files.isRegularFile(file)) {
                throw new SkillRuntimeException("Path is not a file: " + path);
            }
            return Files.newInputStream(file);
        } catch (SkillRuntimeException e) {
            throw e;
        } catch (IOException e) {
            throw new SkillRuntimeException("Failed to open local file: " + path, e);
        }
    }

    @Override
    public String readText(String path, int maxBytes) {
        return new String(readBytes(path, maxBytes), StandardCharsets.UTF_8);
    }

    @Override
    public void writeText(String path, String content) {
        Path file = Paths.get(path).toAbsolutePath().normalize();
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(file, (content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new SkillRuntimeException("Failed to write local file: " + path, e);
        }
    }

    @Override
    public SkillFileInfo stat(String path) {
        Path file = Paths.get(path).toAbsolutePath().normalize();
        try {
            if (!Files.exists(file)) {
                return null;
            }
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            return new SkillFileInfo(file.toString(), attrs.isDirectory(), attrs.size(),
                attrs.lastModifiedTime().toMillis());
        } catch (IOException e) {
            throw new SkillRuntimeException("Failed to stat local path: " + path, e);
        }
    }

    @Override
    public List<SkillFileInfo> listDirectory(String path, int maxDepth, int maxResults) {
        Path root = Paths.get(path).toAbsolutePath().normalize();
        if (Files.isRegularFile(root)) {
            return Collections.singletonList(stat(root.toString()));
        }
        if (!Files.isDirectory(root)) {
            throw new SkillRuntimeException("Path does not exist or is not a directory: " + path);
        }
        List<SkillFileInfo> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root, Math.max(1, maxDepth))) {
            java.util.Iterator<Path> iterator = stream.iterator();
            while (iterator.hasNext() && entries.size() < maxResults) {
                Path entry = iterator.next();
                if (entry.equals(root)) {
                    continue;
                }
                BasicFileAttributes attrs = Files.readAttributes(entry, BasicFileAttributes.class);
                entries.add(new SkillFileInfo(entry.toString(), attrs.isDirectory(), attrs.size(),
                    attrs.lastModifiedTime().toMillis()));
            }
        } catch (IOException e) {
            throw new SkillRuntimeException("Failed to list local directory: " + path, e);
        }
        return entries;
    }

    @Override
    public List<SkillFileInfo> listFiles(String path, final int maxDepth, final int maxResults) {
        final Path root = Paths.get(path).toAbsolutePath().normalize();
        final List<SkillFileInfo> files = new ArrayList<>();
        if (Files.isRegularFile(root)) {
            SkillFileInfo info = stat(root.toString());
            if (info != null) {
                files.add(info);
            }
            return files;
        }
        if (!Files.isDirectory(root)) {
            throw new SkillRuntimeException("Path does not exist or is not a directory: " + path);
        }
        try {
            // 只收集普通文件；glob/grep 不需要目录条目，并通过 maxResults 控制遍历规模。
            Files.walkFileTree(root, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), maxDepth,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (attrs.isRegularFile() && files.size() < maxResults) {
                            files.add(new SkillFileInfo(file.toString(), false, attrs.size(),
                                attrs.lastModifiedTime().toMillis()));
                        }
                        return files.size() >= maxResults ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                    }
                });
            return files;
        } catch (IOException e) {
            throw new SkillRuntimeException("Failed to list local files: " + path, e);
        }
    }
}
